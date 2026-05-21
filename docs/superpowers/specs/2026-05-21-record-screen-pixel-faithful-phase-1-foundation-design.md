# Record Screen — Pixel-Faithful Re-skin · Phase 1 (Foundation) — Design Spec

**Date:** 2026-05-21
**Status:** Approved (owner, 2026-05-21)
**Mockup reference:** `mockups/new_uiux/01-record-home.html`

---

## 1. Context & Goal

For v1.0.0 the owner wants every screen re-skinned **pixel-faithful** to the
`mockups/new_uiux/*.html` reference pages, adapting Compose/Material to the
mockup rather than the reverse. The record screen is first.

The record screen's chrome (status pill, loop pill, camera controls, settings
card, bottom nav) is **shared** across all three modes — Portrait, Landscape,
Portrait+Landscape (DualShot). A diff of the mockup CSS against the current
Compose code found ~15 pixel deltas (radii, alphas, paddings, dot colours) plus
~6 structural ones (two-part loop pill, blinking recording dot, nav-icon
containers, FAB labels, zone-tag de-chipping, decorative overlays).

The full re-skin is **phased by layer** (approach B):

| Phase | Scope |
|---|---|
| **1 — Foundation** *(this spec)* | Inter font + a complete mockup-exact token set. No composable edits. |
| 2 — Shared chrome | Pixel-fix `RecordChrome.kt`: status/loop pills, recording dot, camera controls, settings card (incl. dimmed recording-state revert), bottom nav. Corrects all 3 modes at once. |
| 3 — Mode framing + decorative | Dual zone-tag, split divider, landscape 16:9 letterbox band, grid + vignette + focus-reticle overlays. |

**Phase 1 goal:** establish the Inter typeface and a named, mockup-exact token
set so Phases 2 and 3 consume tokens instead of inventing pixel values. Phase 1
touches only `theme/`, Gradle, one resource file, and one doc — it changes no
behaviour and edits no composable.

---

## 2. Scope

### In scope (Phase 1)

- Inter font integrated as a downloadable `FontFamily` (Google Fonts provider).
- `Typography` (`Type.kt`) rewired from `FontFamily.SansSerif` to Inter.
- `RovaTokens` expanded with the mockup's full type scale (Inter-based).
- New `RecordChromeTokens` object holding the mockup's colour/alpha/dimension
  constants.
- `docs/UI_DESIGN_TOKENS.md` updated to the mockup-exact values.

### Out of scope (Phase 1)

- Any edit to a composable (`RecordChrome.kt`, `DualPreviewZone.kt`,
  `RecordScreen.kt`, `WarningCenter.kt`, …) — that is Phase 2/3. The new
  `RecordChromeTokens` members and new `RovaTokens` styles sit **unused** until
  Phase 2; unused *public* `object` members trip neither the Kotlin compiler
  nor lint, so Phase 1 ships clean.
- Preview rendering (portrait-zone stretch) — owned by open PR #25
  (`feat/dualshot-4-3-source-aspect`).
- CSS `backdrop-filter` blur — Compose has no backdrop blur; the existing
  semi-transparent fills are the accepted approximation (see §10).

---

## 3. Non-Goals

- No new unit tests — Phase 1 is declarative theme data, no logic to exercise.
- No change to `Color.kt` Material `ColorScheme` slots — the mockup chrome
  colours are screen-local glass tints, not M3 surface roles (same reasoning
  the existing `RovaTokens` / `RovaWarnings` objects already follow).
- No `RovaTokensPreview.kt` redesign — it may gain Inter implicitly via the
  `Typography` swap; no deliberate edit.

---

## 4. Components & Files

| File | Action | Responsibility |
|---|---|---|
| `app/src/main/java/com/aritr/rova/ui/theme/Font.kt` | **Create** | Inter `FontFamily` via Google Fonts provider. |
| `app/src/main/res/values/font_certs.xml` | **Create** | Standard Play-services font-provider cert array. |
| `gradle/libs.versions.toml` | Modify | Add `androidx-compose-ui-text-google-fonts` library entry. |
| `app/build.gradle` | Modify | `implementation(libs.androidx.compose.ui.text.google.fonts)`. |
| `app/src/main/java/com/aritr/rova/ui/theme/Type.kt` | Modify | Swap every slot `FontFamily.SansSerif` → `Inter`; delete the 4 resolved "TODO: bundle Inter" comments. |
| `app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt` | Modify | Add 7 mockup type styles; point all type styles at Inter. |
| `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt` | **Create** | Mockup colour/alpha/dimension constants. |
| `docs/UI_DESIGN_TOKENS.md` | Modify | §2.2 type table to mockup scale; new record-chrome constants section; record the Inter decision. |

---

## 5. Inter Font Integration

### Mechanism — downloadable font (owner decision, 2026-05-21)

Inter ships via the AndroidX **Google Fonts provider**
(`androidx.compose.ui:ui-text-google-fonts`, version managed by the existing
Compose BOM `2025.01.01`). No `.ttf` asset, no OFL file in-repo, no APK size
cost.

### `gradle/libs.versions.toml`

Add under `[libraries]`:

```toml
androidx-compose-ui-text-google-fonts = { group = "androidx.compose.ui", name = "ui-text-google-fonts" }
```

### `app/build.gradle`

Add alongside the other Compose dependencies (after `androidx-compose-material3`):

```groovy
implementation(libs.androidx.compose.ui.text.google.fonts)
```

### `app/src/main/res/values/font_certs.xml`

The standard Play-services font-provider certificate array (verbatim from the
AndroidX downloadable-fonts guide):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <array name="com_google_android_gms_fonts_certs">
        <item>@array/com_google_android_gms_fonts_certs_dev</item>
        <item>@array/com_google_android_gms_fonts_certs_prod</item>
    </array>
    <string-array name="com_google_android_gms_fonts_certs_dev">
        <item>
            MIIEqDCCA5CgAwIBAgIJANWFuGx90071MA0GCSqGSIb3DQEBBAUAMIGUMQswCQYD
            VQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQGA1UEBwwNTW91bnRhaW4g
            VmlldzEQMA4GA1UECgwHQW5kcm9pZDEQMA4GA1UECwwHQW5kcm9pZDEQMA4GA1UE
            AwwHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTAe
            Fw0wODA0MTUyMzM2NTZaFw0zNTA5MDEyMzM2NTZaMIGUMQswCQYDVQQGEwJVUzET
            MBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQGA1UEBwwNTW91bnRhaW4gVmlldzEQMA4G
            A1UECgwHQW5kcm9pZDEQMA4GA1UECwwHQW5kcm9pZDEQMA4GA1UEAwwHQW5kcm9p
            ZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTCCASAwDQYJKoZI
            hvcNAQEBBQADggENADCCAQgCggEBANbOLggKv+IxTdGNs8/TGFy0PTP6DHThvbbR
            24kT9ixcOd9W+EaBPWW+wPPKQmsHxajtWjmQwWfna8mZuSeJS48LIgAZlKkpFeVy
            xW0qMBujb8X8ETrWy550NaFtI6t9+u7hZeTfHwqNvacKhp1RbE6dBRGWynwMVX8N
            I07yfNXVDhAjWLHogfBs8FZSs0Zsj7DuHIm6+nW9wYBn+lLfHpQ7qFXLk1+jX5R
            QwO+rdTtJTH/jaJqe3R+TmU0Sj0YHRpIfZ/SaqWoyB/cP2yyiYjf7t7eqUgT/0n
            mfvWmYbB9p9HD7nbZQ+G94vSCBlD1k0CAwEAAaOB9zCB9DAdBgNVHQ4EFgQUhzkS
            9E6G+x8W+l5xMUFmoFPaYtAwgcQGA1UdIwSBvDCBuYAUhzkS9E6G+x8W+l5xMUFm
            oFPaYtChgZqkgZcwgZQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlh
            MRYwFAYDVQQHDA1Nb3VudGFpbiBWaWV3MRAwDgYDVQQKDAdBbmRyb2lkMRAwDgYD
            VQQLDAdBbmRyb2lkMRAwDgYDVQQDDAdBbmRyb2lkMSIwIAYJKoZIhvcNAQkBFhNh
            bmRyb2lkQGFuZHJvaWQuY29tggkA1YW4bH3TTvUwDAYDVR0TBAUwAwEB/zANBgkq
            hkiG9w0BAQQFAAOCAQEAhpcN3LK5Iqg6mLJ/exgabVcv0NRgPbZGTpFQqufLBSCY
            VFqDvHcReXVtJqWHrCnRm7Ylfdh5oXIPpW8KMtRArJX+yWfoTpdrjQiZSjwBVZB1
            tdgVTSi7yV4fOHc8FvT8FwInTPaPwEvL8WzaJSdYsCVH3UE+l8x+l8wU2hUW0+8/
            tKiCpzMfPjT9PpKnyOzWeyfb0FQQNQNXgkbELN3R4kmTb+1lMlTGqkpRBJYqvCB2
            ku6oDxxRJEYrSe9wAZbZ8gAcl/EXc1g3wJEoyrG/QvBJTL4yt+kBM/eFmA+qVgvA
            8bn/Qf9F1y0VLg9hVgEoEPSDPNcfwOPRWPyfvBcbqg==
        </item>
    </string-array>
    <string-array name="com_google_android_gms_fonts_certs_prod">
        <item>
            MIIEQzCCAyugAwIBAgIJAMLgh0ZkSjCNMA0GCSqGSIb3DQEBBAUAMHQxCzAJBgNV
            BAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBW
            aWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4G
            A1UEAxMHQW5kcm9pZDAeFw0wODA4MjEyMzEzMzRaFw0zNjAxMDcyMzEzMzRaMHQx
            CzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3Vu
            dGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9p
            ZDEQMA4GA1UEAxMHQW5kcm9pZDCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgC
            ggEBAKtWLgDYO6IIrgqWbxJOKdoR8qtW0I9Y4sypEwPpt1TTcvZApxsdyxMJZ2Jm
            Rft/T8q0wOLO+jLm0xHpx3wndPdxjPCqIy0byjf3T1+ FoVa5KZWvjLPbgWVoogo
            pxAiR9bdRdfdgADC2YJ8nT8APP0iY3FHJ8dEd2cm1m0iA0gMTBOMD8PoEvljLfx
            QuPSdBUx02hcZQ8R+iiAWdjsfQI9XMRmF93FT9Hd+G2bM9G2OdJ9JJzVPLwQ8gN
            xHZ8h0EAh0qX9ph0c2YDtNm0jv2vYJ5l4xHfXkpljd0AnB6XF0QWS6+1mqYjEEdL
             kVvP0wRyMzVzXSi6r0d8WHwm9dECAwEAAaOB2DCB1TAdBgNVHQ4EFgQUYn0CtnK5
            kIPJjQrG3i4Ke3v4LhAwgaUGA1UdIwSBnTCBmoAUYn0CtnK5kIPJjQrG3i4Ke3v4
            LhChgX9kgXwwejELMAkGA1UEBhMCVVMxEzARBgNVBAgTCkNhbGlmb3JuaWExFjAU
            BgNVBAcTDU1vdW50YWluIFZpZXcxFDASBgNVBAoTC0dvb2dsZSBJbmMuMRAwDgYD
            VQQLEwdBbmRyb2lkMRAwDgYDVQQDEwdBbmRyb2lkggkAwuCHRmRKMI0wDAYDVR0T
            BAUwAwEB/zANBgkqhkiG9w0BAQQFAAOCAQEAYzhMmJrk+5h6oHL2vPzdSh3Kh2vy
            i2L8jK2pPNN3xV5hr0v4DI8DZdwQyXz3HGHFh2pE45jNbBYwGoTWfPwh2J9eXfDw
            t8d+ZXG0ULFf8r8MhUk0aT3KCqEt0CtnFsxQNUOJqz4AL6xk4F0Y8oU0XfDpgZD
            6dT5JqHV3zZ4nm8wQVjPaPM1d4OFqZ6jWv2bMTr1MnQ3pXLqcc8tCfwQRfGiOSm
            ESJFGNJ3PvqU0iVfUJjY9N0L5fT6yQQ1MnX8H1cqQ9bFKP3eAQ0nQQVL3Tnk2vJ
            J9bJ8E8jJ4w8VfXlwQAvqFnXr1WHxnNvT5dV0+ASCV0KTV1zhDWSL8MaQ==
        </item>
    </string-array>
</resources>
```

> The cert blobs above are the canonical AndroidX values, line-wrapped for the
> doc. The implementer copies the exact, current `font_certs.xml` content from
> the official AndroidX downloadable-fonts sample at implementation time — the
> certs are a fixed, published constant; do **not** hand-transcribe.

### `app/src/main/java/com/aritr/rova/ui/theme/Font.kt` (new)

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
 * fallback while the first cold-launch fetch resolves.
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

---

## 6. Typography Rewire (`Type.kt`)

Every `Typography` slot currently sets `fontFamily = FontFamily.SansSerif`.
Phase 1 swaps each to `Inter`. The `NumericMonoLarge` / `NumericMonoMedium`
top-level styles keep `FontFamily.Monospace` — they are deliberate tabular
timer faces, not body text, and the mockup's timers are equally monospace.

- All 4 `// TODO: bundle Inter font asset (...)` comments are deleted — the
  TODO is now resolved.
- No size / weight / lineHeight / letterSpacing change in this file — the M3
  slot metrics are unchanged; only the typeface moves. (The mockup-exact
  *record-chrome* type scale lives in `RovaTokens`, §7.)

---

## 7. Token Set

### 7.1 `RovaTokens` — type scale additions

`RovaTokens` already carries `eyebrow`, `statusPillLabel`, `cellValue`,
`cellKey`. Phase 1 (a) points all four at `Inter`, and (b) adds the 7 styles
below — each mapped 1 px → 1 sp from the mockup CSS. ALL-CAPS styles are
upper-cased at the call site (existing convention); the `TextStyle` carries
font/weight/spacing only.

| New token | CSS class | size | weight | letterSpacing | features |
|---|---|---|---|---|---|
| `loopCount` | `.loop-count` | 21 sp | SemiBold (600) | −0.6 sp | `tnum` |
| `loopUnit`  | `.loop-unit`  | 10 sp | Normal (400)   | 1.0 sp  | — |
| `statusMain`| `.status-main`| 11 sp | Normal (400)   | 0.1 sp  | — |
| `statusTime`| `.status-time`| 11 sp | Light (300)    | 0 sp    | `tnum` |
| `swipeLabel`| `.swipe-label`| 8 sp  | Normal (400)   | 1.4 sp  | — |
| `navTxt`    | `.nav-txt`    | 9 sp  | Normal (400)   | 0.6 sp  | — |
| `zoneTag`   | `.cam-zone-tag`| 7.5 sp | Medium (500)  | 1.5 sp  | — |

Existing styles, confirmed already mockup-correct (no metric change, Inter
applied): `eyebrow` 9/Medium/2.0 · `statusPillLabel` 11/Medium/`tnum` ·
`cellValue` 12/Medium/`tnum` · `cellKey` 8/Normal/0.8.

> `cellValue` is 12 sp Medium — the mockup `.s-val` is 12 px weight 500. Match. ✔
> `cellKey` is 8 sp — the mockup `.s-key` is 7.5 px; 8 sp is the nearest whole
> sp and is already shipped. Kept at 8 sp (sub-sp precision is not meaningful
> at this size); noted, not changed.

### 7.2 `RecordChromeTokens` (new object)

`app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt` — a focused
object for the record screen's mockup pixel constants. Record-screen-scoped so
it cannot over-apply elsewhere (the same rationale `RovaTokens`' KDoc gives).
Phase 2/3 consume it; it is unused in Phase 1 by design.

**Colours / alphas** (`androidx.compose.ui.graphics.Color`):

| Token | Mockup source | Value |
|---|---|---|
| `glassFill` | `.status-pill` / `.loop-pill` bg | `Color.Black.copy(alpha = 0.40f)` |
| `glassStroke` | their border | `Color.White.copy(alpha = 0.07f)` |
| `camControlFill` | `.cam-ctrl-btn` bg | `Color.Black.copy(alpha = 0.38f)` |
| `camControlStroke` | `.cam-ctrl-btn` border | `Color.White.copy(alpha = 0.09f)` |
| `settingsCardFill` | `.settings-card` bg | `Color.White.copy(alpha = 0.065f)` |
| `settingsCardStroke` | `.settings-card` border | `Color.White.copy(alpha = 0.09f)` |
| `cellDivider` | `.s-cell + .s-cell` border | `Color.White.copy(alpha = 0.07f)` |
| `bottomNavFill` | `.bottom-nav` bg | `Color.Black.copy(alpha = 0.50f)` |
| `bottomNavTopStroke` | `.bottom-nav` border-top | `Color.White.copy(alpha = 0.055f)` |
| `dotIdle` | `.dot-idle` | `Color.White.copy(alpha = 0.25f)` |
| `dotRecording` | `.dot-recording` | `Color(0xFFEF4444)` |
| `dotBreak` | `.dot-break` | `Color(0xFF94A3B8)` |
| `fabStartFill` | `.btn-start` bg | `Color.White.copy(alpha = 0.07f)` |
| `fabStartStroke` | `.btn-start` border | `Color.White.copy(alpha = 0.15f)` |
| `fabStopFill` | `.btn-stop` bg | `Color(0xFFEF4444).copy(alpha = 0.12f)` |
| `fabStopStroke` | `.btn-stop` border | `Color(0xFFEF4444).copy(alpha = 0.30f)` |
| `fabStopRing` | `.btn-stop::after` | `Color(0xFFEF4444).copy(alpha = 0.10f)` |
| `stopSquare` | `.stop-sq` | `Color(0xFFEF4444)` |
| `splitDivider` | `.cam-split-divider` | `Color.White.copy(alpha = 0.14f)` |
| `camZoneBackground` | `.cam-zone` bg | `Color(0xFF060D18)` |
| `cameraGridLine` | `.camera-grid` | `Color.White.copy(alpha = 0.018f)` |
| `focusFrameStroke` | `.focus-frame` (0.8 × 0.25 opacity) | `Color.White.copy(alpha = 0.20f)` |

**Text-colour alphas** — stored as `Color` (white at the mockup alpha):
`loopCountText` 0.93 · `loopUnitText` 0.32 · `statusMainText` 0.65 ·
`statusTimeText` 0.32 · `cellValueText` 0.88 · `cellKeyText` 0.28 ·
`cellValueReadOnlyText` 0.50 *(read-only Mode cell — existing behaviour)* ·
`settingsArrow` 0.18 · `swipeHint` 0.22 · `navIcon` 0.35 · `navText` 0.30 ·
`zoneTagText` 0.32.

**Dimensions** (`androidx.compose.ui.unit.dp` / `.sp`):

| Token | Mockup | Value |
|---|---|---|
| `statusPillRadius` | `.status-pill` | 20.dp |
| `loopPillRadius` | `.loop-pill` | 11.dp |
| `statusPillPaddingH` / `…V` | `6px 11px` | 11.dp / 6.dp |
| `loopPillPaddingH` / `…V` | `8px 13px` | 13.dp / 8.dp |
| `pillContentGap` | `.status-pill` gap | 7.dp |
| `loopPillContentGap` | `.loop-pill` gap | 6.dp |
| `topOverlayGap` | `.top-overlay` gap | 8.dp |
| `dotSize` | `.dot` | 6.dp |
| `camControlSize` | `.cam-ctrl-btn` | 30.dp |
| `camControlGap` | `.cam-controls` gap | 7.dp |
| `settingsCardRadius` | `.settings-card` | 14.dp |
| `settingsCardPaddingH` / `…V` | `7px 12px` | 12.dp / 7.dp |
| `settingsCellPaddingH` | `.s-cell` | 3.dp |
| `settingsWrapGap` | `.settings-wrap` gap | 7.dp |
| `settingsCardBottomInset` | `.settings-wrap` bottom | 110.dp |
| `swipeBarWidth` / `…Height` | `.swipe-bar` | 30.dp / 2.dp |
| `bottomNavHeight` | `.bottom-nav` | 106.dp |
| `bottomNavPaddingH` | `.bottom-nav` | 28.dp |
| `bottomNavPaddingBottom` | `.bottom-nav` | 18.dp |
| `navItemGap` | `.nav-item` gap | 5.dp |
| `navIconBoxSize` | `.nav-ico` | 42.dp |
| `navIconGlyphSize` | inner `<svg>` | 20.dp |
| `navIconCornerRadius` | `.nav-ico` | 12.dp |
| `fabSize` | `.center-btn` | 56.dp |
| `fabStopRingInset` | `.btn-stop::after` | 5.dp |
| `stopSquareSize` / `…Radius` | `.stop-sq` | 18.dp / 4.dp |
| `screenEdgeMargin` | `.top-overlay` left/right | 16.dp |
| `splitDividerHeight` | `.cam-split-divider` | 2.dp |
| `zoneTagPaddingEnd` / `…Bottom` | `.cam-zone-tag` | 13.dp / 9.dp |
| `focusFrameSize` | `.focus-frame` | 60.dp |

> Some of these duplicate values already in `RovaTokens` (`camControlGap`,
> `camControlSize`, `screenEdgeMargin`, `recordCardBottomInset`,
> `statusDotSize`). `RecordChromeTokens` is the **mockup-traceable** home for
> the record screen; Phase 2 migrates `RecordChrome.kt`'s private `val`s and
> the relevant `RovaTokens` references to it, then the `RovaTokens` duplicates
> are removed in Phase 2's cleanup. Phase 1 only *adds* — no removal, so no
> call-site churn this phase.

---

## 8. Documentation (`docs/UI_DESIGN_TOKENS.md`)

- §2.2 (typography): replace the type table with the mockup-exact scale (§7.1
  here); note the M3 `Typography` slots now use Inter.
- Add a new section "Record-chrome constants" mirroring §7.2.
- Record the decision: **Inter via the Google Fonts downloadable provider**
  (rationale: no asset/licence scope, no APK cost; trade-off: Play-services
  dependency + first-launch fallback frame).
- Remove the "TODO: bundle Inter font asset" references now that it is done.

---

## 9. Verification

Phase 1 changes no behaviour. Gates (run by subagents — gradle is
subagent-routed):

1. `:app:assembleDebug` — BUILD SUCCESSFUL (proves the new dependency
   resolves, `Font.kt` / `RecordChromeTokens.kt` compile, `font_certs.xml` is
   a valid resource).
2. `:app:testDebugUnitTest` — the existing suite (1026 tests / 82 classes /
   0-0-0 per the last master baseline) stays **green and unchanged**. No new
   tests (§3).
3. `:app:lintDebug` — issue count unchanged vs the master baseline (53:
   50W + 3H + 0E). A downloadable-font setup adds no lint findings; the new
   public token members are not lint-eligible.
4. **On-device launch smoke** (owner) — install on the Samsung SM-A176B,
   launch, confirm text renders in Inter (compare a heading against the
   mockup), and confirm no blank-text flash beyond the first cold frame.

Diff allowlist — exactly these 8 paths, nothing else:
`theme/Font.kt`, `theme/RecordChromeTokens.kt`, `res/values/font_certs.xml`,
`gradle/libs.versions.toml`, `app/build.gradle`, `theme/Type.kt`,
`theme/RovaTokens.kt`, `docs/UI_DESIGN_TOKENS.md`.

---

## 10. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| Downloadable font needs Google Play Services. | Low | Target device (SM-A176B) has it; `FontFamily.SansSerif` fallback covers absence — text never blanks, only renders Roboto. |
| First cold-launch fetch flashes Roboto for ~1 frame. | Low | Accepted for v1.0.0; the fallback chain prevents any blank state. Google Play caches the font after first fetch. |
| CSS `backdrop-filter` blur (20/24/36 px) is not replicable in Compose — no backdrop blur API. | Low | Out of scope. The semi-transparent glass fills are the established approximation (already how the shipped code looks). Blur radii are deliberately **not** tokenised. If the owner later insists, a `RenderEffect`-based approach is its own ticket. |
| New token objects unused until Phase 2 → dead-code worry. | None | Unused *public* `object` members trip neither the Kotlin compiler nor Android lint. Confirmed by the existing `RovaTokens` members that are already only partially consumed. |
| Token duplication between `RovaTokens` and `RecordChromeTokens`. | Low | Intentional and temporary — Phase 2 migrates call sites then deletes the `RovaTokens` duplicates. Phase 1 is add-only to avoid call-site churn. |

---

## 11. Rejected Alternatives

- **Bundle Inter `.ttf` in `res/font/`** — deterministic, no Play-services
  dependency, but adds binary assets + an OFL licence file to the repo and APK
  size. Owner chose downloadable (2026-05-21).
- **Single PR for the whole record-screen re-skin** — rejected (approach A):
  couples Inter + custom vectors + chrome rewrite + overlays + the R2
  settings-card revert into one un-reviewable diff.
- **Fold chrome constants into `RovaTokens`** — rejected: bloats a
  general-purpose object with record-screen-specific values, against the
  "don't over-apply" rationale in `RovaTokens`' own KDoc.
- **Phase 1 also applies tokens to composables** — rejected: that *is* Phase 2.
  Keeping Phase 1 to `theme/` makes it a zero-behaviour, trivially-reviewable
  foundation.
- **Tokenise the blur radii** — rejected: Compose cannot consume them; dead
  constants. See §10.

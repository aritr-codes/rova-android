# Record Screen Pixel-Faithful Re-skin — Phase 2 (Shared Chrome) Design

**Date:** 2026-05-22
**Status:** Design — awaiting owner review
**Phase 1 (Foundation):** merged to `master` @ `ed574f4` (Inter font, `RovaTokens` type scale, `RecordChromeTokens`).

---

## 1. Goal

Re-skin the record screen's **shared chrome** — the overlay UI common to all three modes (Portrait, Landscape, Portrait+Landscape) — to pixel-match `mockups/new_uiux/01-record-home.html`, by wiring the composables in `RecordChrome.kt` to the Phase-1 tokens and correcting every measured pixel/colour/typography delta.

Phase 1 established the tokens but consumed none of them. Phase 2 is where the screen visibly changes.

## 2. Background — what Phase 1 left

Phase 1 landed, with **zero composable edits**:
- `Inter` downloadable `FontFamily`; `Typography` + `RovaTokens` styles rewired to it.
- `RovaTokens` gained 7 mockup type styles: `loopCount`, `loopUnit`, `statusMain`, `statusTime`, `swipeLabel`, `navTxt`, `zoneTag`.
- `RecordChromeTokens` — ~70 mockup-exact colour/alpha/dimension constants. **Currently unused.**

A pre-design exploration diffed the current `RecordChrome.kt` rendering against the mockup CSS. Result: ~70% already matches; the remainder is a bounded delta list (§5). **Every mockup value Phase 2 needs already has a Phase-1 token** — Phase 2 adds no new tokens.

## 3. Scope

**In scope — the shared chrome:**
- `RecordTopOverlay` / `StatusPill` / `StatusDot` — top status pill + status dots (idle and active).
- `LoopPill` — the active-HUD loop counter pill.
- `RecordActiveHud` — the active-recording HUD wrapper (loop pill + status pill).
- `RecordCameraControls` / `GlassCircleButton` — flash + flip glass buttons.
- `RecordSettingsCard` / `SettingsCell` / `CellSep` — the 5-cell settings row + swipe hint.
- `RecordBottomNav` / `NavItem` / `RecordFab` — Library · FAB · Settings bottom bar.
- `RecordRecoveryChip` — the interrupted-recording chip (token-wire only; already structurally correct).
- 6 custom vector icons for the chrome glyphs.

**Out of scope:**
- Mode-specific framing (dual zone-tags, split divider, landscape letterbox) — **Phase 3**.
- Decorative overlays (rule-of-thirds grid, edge vignette, focus-bracket reticle) — **Phase 3**.
- Preview rendering / portrait-zone aspect — owned by a separate workstream (PR #25).
- Any `service/**`, recording pipeline, or warning-precedence logic.

## 4. File structure (Approach A — in-place + dedicated icons file)

| File | Action | Responsibility |
|---|---|---|
| `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` | Modify | Token-wire every chrome composable; sizing fixes; recording-dot blink+glow; `dimmed` param on the settings card. |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordChromeIcons.kt` | Create | `object RecordChromeIcons` — 6 custom `ImageVector`s for the chrome glyphs. |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` | Modify | Compose the settings card during active sessions (dimmed); thread the new params. **Start-gate region untouched.** |
| `docs/UI_DESIGN_TOKENS.md` | Modify | One short note: the record chrome now consumes `RovaTokens` + `RecordChromeTokens` (the tokens are no longer "declared but unused"). |

**Approach A rationale:** `RecordChrome.kt` is already ~521 lines. Inlining 6 vector-path blocks (bulky declarative path data) would push it past 700 and bury layout logic. A dedicated `RecordChromeIcons.kt` isolates the path data and keeps the layout file focused. A full per-element split of `RecordChrome.kt` (one file per chrome element) was rejected — it is a restructure not chartered by a pixel re-skin and would churn history against the byte-identical-invariant audit.

## 5. Component changes — `RecordChrome.kt`

### 5.1 Style constants → tokens
The private style constants are deleted; call sites reference `RecordChromeTokens`:

| Deleted constant | Replaced by |
|---|---|
| `GlassFill` | `RecordChromeTokens.glassFill` |
| `GlassStroke` | `RecordChromeTokens.glassStroke` |
| `RecordingDotColor` | `RecordChromeTokens.dotRecording` |
| `WaitingDotColor` (`#FBBF24` amber) | `RecordChromeTokens.dotBreak` (`#94A3B8` slate) — **colour correction** |
| `MergingDotColor` | kept as a named constant — `Color(0xFF60A5FA)` inline (the mockup defines only idle/recording/break dots; `RecordChromeTokens` has no merging-dot token, and Phase 2 does not invent one) |
| `StatusPillShape` / `PillShape` | `RoundedCornerShape(RecordChromeTokens.statusPillRadius)` / `loopPillRadius` |
| `SettingsCardShape` / `SettingsCardFill` / `SettingsCardStroke` | `settingsCardRadius` / `settingsCardFill` / `settingsCardStroke` |
| `CellDivider` | `cellDivider` |
| `BottomNavFill` / `BottomNavStroke` | `bottomNavFill` / `bottomNavTopStroke` |
| `ControlBtnSize` | `camControlSize` |
| `FabSize` | `fabSize` |

The merging dot keeps its current `Color(0xFF60A5FA)` inline — the mockup defines only idle/recording/break, so Phase 2 does not invent a token it never specified.

### 5.2 Typography → `RovaTokens`
Replace every `MaterialTheme.typography.*` chrome-text reference with the matching `RovaTokens` style — this is what applies the missing letter-spacing, the correct weights, and `tnum`:

| Composable text | Was | Now |
|---|---|---|
| Status-pill primary label | `typography.bodyMedium` + alpha | `RovaTokens.statusMain` (11sp/400/+0.1) |
| Status-pill trailing time | `typography.bodyMedium` + alpha | `RovaTokens.statusTime` (11sp/**300 Light**/`tnum`) — weight correction |
| Loop-pill numeral | not rendered with a token | `RovaTokens.loopCount` (21sp/600/−0.6/`tnum`) |
| Loop-pill caption | not rendered with a token | `RovaTokens.loopUnit` (10sp/400/+1.0) |
| Settings-cell value | `typography.labelLarge` | `RovaTokens.cellValue` (12sp/500/`tnum`) |
| Settings-cell key | `typography.labelSmall` (~8sp) | `RovaTokens.cellKey` (**7.5sp**/400/+0.8) — size correction |
| Swipe hint | `typography.labelSmall` | `RovaTokens.swipeLabel` (8sp/400/+1.4) |
| Bottom-nav label | `typography.labelSmall` (~8sp) | `RovaTokens.navTxt` (**9sp**/400/+0.6) — size correction |

Text **colours** use the `RecordChromeTokens.*Text` tokens (`statusMainText`, `statusTimeText`, `loopCountText`, `loopUnitText`, `cellValueText`, `cellKeyText`, `cellValueReadOnlyText`, `navText`) rather than `Color.White.copy(alpha = …)` literals.

### 5.3 Sizing corrections
All values already exist as `RecordChromeTokens` constants:

| Element | Current | Mockup / token |
|---|---|---|
| Nav-item icon→label gap | `3.dp` | `RecordChromeTokens.navItemGap` = 5dp |
| Nav-icon box | `34.dp` icon slot | `navIconBoxSize` 42dp, glyph `navIconGlyphSize` 20dp inside, `navIconCornerRadius` 12dp |
| Settings-card vertical padding | `8.dp` | `settingsCardPaddingV` = 7dp |
| Bottom-nav bottom padding | `14.dp` | `bottomNavPaddingBottom` = 18dp |
| FAB-stop fill alpha | `0.13` | `fabStopFill` = red @ 0.12 |
| FAB-stop outer ring | not rendered | `fabStopRing` + `fabStopRingInset` (5dp outward) |

### 5.4 FAB-stop outer ring
The mockup's `.btn-stop::after` is a 1px ring inset `-5px` (extends outward) at `rgba(239,68,68,0.10)`. Rendered as a `Box` border drawn behind the FAB at `RecordChromeTokens.fabSize + 2*fabStopRingInset`, colour `fabStopRing`. Present only in the Stop state.

## 6. Custom icons — `RecordChromeIcons.kt`

`object RecordChromeIcons` exposes 6 chrome glyphs as `ImageVector`s, path data transcribed verbatim from the mockup's inline `<svg>` `path d="…"` strings:

| Val | Glyph | Call site |
|---|---|---|
| `flashAuto` | lightning + "A" / auto state | `RecordCameraControls` flash button |
| `flashOn` | filled lightning | flash button |
| `flashOff` | lightning with slash | flash button |
| `flipCamera` | camera-rotate arrows | `RecordCameraControls` flip button |
| `library` | the bottom-nav Library glyph | `RecordBottomNav` Library `NavItem` |
| `settings` | the bottom-nav Settings glyph | `RecordBottomNav` Settings `NavItem` |
| `fabPlay` | the FAB start triangle | `RecordFab` Start state |

The FAB **stop** indicator is a plain rounded square (`RecordChromeTokens.stopSquare` fill, `stopSquareSize`/`stopSquareRadius`) — drawn as a `Box`, not a vector.

Flash is tri-state; the existing `flashMode` cycling logic in `RecordCameraControls` is preserved — only the rendered glyph swaps from `Icons.Default.*` to the matching `RecordChromeIcons.flash*` vector. Each `ImageVector` is built once as a lazy `val` (cached, not rebuilt per recomposition).

The plan task that builds this file fetches the exact `path d=` strings from `mockups/new_uiux/01-record-home.html` and the sibling mockup pages; if a glyph's SVG is not present in the mockup, that is escalated rather than guessed.

## 7. Recording-dot animation — blink + glow

`StatusDot` in the **Recording** state gains motion (idle / break / merging dots stay static):

- **Blink:** `rememberInfiniteTransition` drives the dot's alpha on a ~1.8s ease-in-out loop (mockup `blink 1.8s ease-in-out infinite`), pulsing between full and a dimmed alpha.
- **Glow:** Compose has no backdrop/box-shadow API. The mockup's `box-shadow: 0 0 8px rgba(239,68,68,1)` is approximated with `Modifier.drawBehind` painting a radial-gradient halo (red → transparent) of ~8dp radius behind the 6dp dot. The halo alpha rides the same blink transition so glow and dot pulse together.

The animation is self-contained in `StatusDot` — no hoisted state, no caller change. It runs only while the Recording dot is composed (active session), so there is no idle-screen battery cost.

## 8. Settings card dimmed during recording

The mockup shows the settings card present-but-dimmed during recording. This **reverses the R2 idle-only decision**.

- `RecordSettingsCard` gains a `dimmed: Boolean = false` parameter.
- When `dimmed`: the card is wrapped in `Modifier.alpha(0.75f)` and its tap (`onOpenSheet`) is disabled — read-only chrome, matching the mockup's non-interactive dimmed state.
- The 5-cell content still reflects live settings; only opacity + interactivity change.

## 9. `RecordScreen.kt` integration

- `RecordScreen` currently composes `RecordSettingsCard` for the idle state only. Phase 2 composes it during active sessions too, with `dimmed = true`.
- The dimmed card sits bottom-centre (`settingsCardBottomInset` 110dp); the active HUD sits top-centre — no overlap.
- New params are threaded at the existing call sites; no new state is hoisted.
- **Hard invariant:** the Start-gate block (`RecordScreen.kt` ~L107-122), `WarningId`, `WarningPrecedence`, `WarningCenter`, `onStart`, and the recovery-echo path are **not touched**. Phase 2 edits only the chrome-rendering call sites and the settings-card visibility branch.

## 10. Hard invariants (must be byte-identical after Phase 2)

- `WarningId` / `WarningPrecedence` / `WarningCenterViewModel` / the VM-factory wiring / `RecordScreen` Start-gate.
- `ui/warnings/**` and their tests.
- All `service/**`, `dualrecord/**`, recording-pipeline code.
- The existing unit-test suite: **1026 tests / 82 classes / 0-0-0**, byte-identical — Phase 2 adds, removes, changes **zero** tests.

## 11. Testing & gates

Phase 2 changes are Compose composables — layout, colour, typography, a self-contained animation. The project has **no Robolectric / Compose-UI-test layer** for chrome (the JVM test layer cannot exercise composition); this matches the Phase 1, R1, and R2 precedent of no unit tests for pixel-level UI work.

**No new unit tests.** Verification is:
- `:app:assembleDebug` — BUILD SUCCESSFUL (proves every edited composable + the new icons file compile).
- `:app:testDebugUnitTest` — **1026 / 82 / 0 / 0 / 0**, byte-identical to the `ed574f4` baseline.
- `:app:lintDebug` — **≤ 53 issues** (no rise vs baseline).
- **On-device smoke** — owner-run on the Samsung SM-A176B: compare the re-skinned chrome side-by-side with `01-record-home.html` across idle / recording / break states; confirm the recording-dot blink+glow; confirm the dimmed settings card during recording.

Gradle is subagent-routed (the controller does not run long gradle calls).

## 12. Diff allowlist

`git diff master..HEAD --name-only` must equal exactly:
```
app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
app/src/main/java/com/aritr/rova/ui/screens/RecordChromeIcons.kt
app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
docs/UI_DESIGN_TOKENS.md
```
Any path beyond these 4 means a hard-invariant file or an out-of-scope composable was touched — revert it.

## 13. Owner follow-ups (not implementer scope)

- On-device smoke (§11).
- **Phase 3** — mode-specific framing (dual zone-tags, split divider, landscape letterbox) + decorative overlays (grid, vignette, focus reticle). Own spec → plan → PR.
- After Phase 2 merges-ready: push `master` (carries Phase 1) + the Phase 2 branch, open the PR (base `master`) — per the owner's stated sequencing.

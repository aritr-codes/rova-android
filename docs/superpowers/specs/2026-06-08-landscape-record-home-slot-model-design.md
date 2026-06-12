# PR-β — Landscape Record-home Slot Model (Design Spec)

**Date:** 2026-06-08
**Status:** Approved (owner-approved design + mockup 2026-06-08)
**ADR:** ADR-0029 §B (slot-based β before enum-collapse γ)
**Visual language:** ADR-0028 liquid-glass
**Companion mockup:** `landscape_record_home_mockup.html` (repo root, untracked)
**Branch (planned):** `feat/liquid-glass-landscape-chrome` off master `e9a2f39`

---

## 1. Goal

Make Record-home re-place its chrome when the device is in landscape, instead of
stretching the single portrait stack sideways. Do it through a **slot model**:
each region's placement is decided by a pure, JVM-testable helper keyed on
`(slot, orientation)`, so PR-γ's orientation picker later drops into an existing
landscape slot without a second redesign.

PR-β is **chrome layout only**. It does not touch the camera, rotation math,
service, recording, or recovery paths. PR-α (merged `e9a2f39`) already makes the
camera *output* device-driven; β is the orthogonal *UI* half.

## 2. Context & premise verification

Record-home (`RecordScreen.kt`) today is a single `Box(fillMaxSize)` with every
region anchored by an `Alignment` constant + fixed padding. There is **zero**
orientation branching (verified: no `LocalConfiguration.orientation`, no
`BoxWithConstraints`, no `WindowSizeClass`). In landscape every region keeps its
portrait anchor and stretches across the wide edge — the owner's "disorienting"
complaint.

**Premise holds (verified in manifest + source):**
- `MainActivity` has **no** `android:screenOrientation` lock and **no**
  programmatic `requestedOrientation`. The Activity follows the sensor and can
  enter landscape configuration.
- `MainActivity` declares `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`.
  Rotation is therefore an in-place configuration change: **no Activity recreate,
  no CameraX rebind.** `LocalConfiguration.orientation` updates and Compose
  recomposes.

Consequence: β is a pure Compose layout response to `LocalConfiguration.orientation`.
No manifest change. No orientation-unlock. No camera-rebind handling.

## 3. Design decisions (codex-reconciled)

The bottom nav bar conflates two things that follow **opposite** landscape
conventions: app **navigation** (Library / Settings tabs → Material puts a nav
rail on the leading/left edge) and the record **action** (Start/Stop FAB →
camera-app convention puts the shutter on the right edge). Rova is a
setup-and-mount recorder (dominant interaction is idle setup, not thumb-firing a
shutter), so the real win is "nothing stretches or drifts," and the record
command still earns the strongest landscape position.

**Landscape zone map:**

```
+----------------------------------------------------+
| (.) status                          [flash][flip]  |  top: status (left) · cam ctrl (right) · HUD (center)
| [recovery]                                          |
| [warning]                                           |
|                                                     |
| [lib]              CAMERA  PREVIEW        +------+   |
| [set]   <- quiet nav rail (left)          | (O)  |  <- record command (right-center)
|                                           +------+   |
|              [ params / mode card ]   (centered, capped)   bottom-center, max-width
+----------------------------------------------------+
```

- **Right-edge center** = Start/Stop FAB. The one command that dominates.
- **Left edge center** = quiet nav rail (Library + Settings). Secondary; does not
  compete with the record button.
- **Bottom-center, max-width** = params/settings card (horizontal reading:
  duration · interval · quality · mode chip). **This is the slot PR-γ's
  orientation picker drops into** — it already houses the mode chip.
- **Top** = status pill (left), camera controls (right), active HUD (center).
  Semantics unchanged; only width/padding and internal axis go landscape-aware.
  The active-HUD **live region keeps its same composable** in both orientations —
  relocating a TalkBack live region risks an a11y regression (ADR-0020) for no
  gain.

Portrait is unchanged: regions route through the helper but with values identical
to today's, so there is zero portrait visual change.

## 4. Architecture

### 4.1 Pure placement helper (the testable seam)

New file `service-free` pure helper under
`app/src/main/java/com/aritr/rova/ui/screens/chrome/ChromeSlotPlacement.kt`
(or `ui/screens/`, matching where RecordChrome lives). Pure Kotlin — only
`Int` dp values and enums; **no Compose / Android imports**, so it runs under
`isReturnDefaultValues = true`.

```kotlin
enum class ChromeOrientation { PORTRAIT, LANDSCAPE }

/** Re-placeable Record-home regions. */
enum class ChromeSlot {
    STATUS_OVERLAY,   // idle status pill
    CAMERA_CONTROLS,  // flash + flip
    RECOVERY_CHIP,    // idle recovery nudge
    WARNING_CENTER,   // idle warning surface
    ACTIVE_HUD,       // recording HUD (loop + status + bar)
    PARAMS_CARD,      // settings/params card (holds the mode chip; γ's picker slot)
    NAV_RAIL,         // Library + Settings destinations
    RECORD_ACTION,    // Start/Stop FAB
}

/** Semantic anchor; mapped to a Compose Alignment at the call site. */
enum class SlotAnchor {
    TOP_START, TOP_END, TOP_CENTER,
    BOTTOM_CENTER, CENTER_START, CENTER_END,
}

/**
 * Inset semantics depend on the anchor family:
 *  - Corner (TOP_START / TOP_END): mainInsetDp = top inset, crossInsetDp = start/end inset.
 *  - Edge-center (TOP_CENTER / BOTTOM_CENTER): mainInsetDp = inset from that edge;
 *    crossInsetDp ignored (centered horizontally).
 *  - Side-center (CENTER_START / CENTER_END): crossInsetDp = inset from that side;
 *    mainInsetDp ignored (centered vertically).
 * maxWidthDp non-null => constrain width and center on the cross axis.
 */
data class SlotPlacement(
    val anchor: SlotAnchor,
    val mainInsetDp: Int,
    val crossInsetDp: Int,
    val maxWidthDp: Int? = null,
)

fun placementFor(slot: ChromeSlot, orientation: ChromeOrientation): SlotPlacement
```

The helper is the **only** decision point for where a slot lives. It is the unit
of test. The exact returned record per `(slot, orientation)` is pinned by tests
(§7), including a regression pin asserting portrait values equal today's layout.

### 4.2 Placement table

Portrait values reproduce today's layout exactly (the "today" column is the
regression pin). Insets are dp. `—` = ignored for that anchor family.

| Slot | Portrait anchor | P main | P cross | P maxW | Landscape anchor | L main | L cross | L maxW |
|------|-----------------|:------:|:-------:|:------:|------------------|:------:|:-------:|:------:|
| STATUS_OVERLAY  | TOP_START    | 16  | 16 | —   | TOP_START    | 16 | 16 | —   |
| RECOVERY_CHIP   | TOP_START    | 70  | 16 | —   | TOP_START    | 54 | 16 | —   |
| WARNING_CENTER  | TOP_START    | 110 | 16 | —   | TOP_START    | 92 | 16 | —   |
| CAMERA_CONTROLS | TOP_END      | 7   | 7  | —   | TOP_END      | 12 | 12 | —   |
| ACTIVE_HUD      | TOP_CENTER   | 16  | —  | —   | TOP_CENTER   | 12 | —  | 360 |
| PARAMS_CARD     | BOTTOM_CENTER| 120 | —  | null| BOTTOM_CENTER| 12 | —  | 360 |
| NAV_RAIL        | BOTTOM_CENTER| 18  | —  | null| CENTER_START | —  | 12 | —   |
| RECORD_ACTION   | BOTTOM_CENTER| 18  | —  | null| CENTER_END   | —  | 14 | —   |

Notes:
- Portrait `PARAMS_CARD` keeps `maxWidthDp = null` → fills width with the existing
  horizontal margin token; landscape caps at 360 and centers.
- Portrait `NAV_RAIL` / `RECORD_ACTION` rows are **advisory** — portrait renders
  the intact bottom bar (§4.3), not these slots. They are defined for helper
  totality and asserted in tests, but `RecordScreen` only consults them in
  landscape.

### 4.3 The one region that decomposes: nav

- **Portrait:** render the existing `RecordBottomNav` bar intact (gradient ramp +
  `Row(Library · FAB · Settings)`). No change. This preserves the bottom-bar
  visual exactly.
- **Landscape:** do **not** render `RecordBottomNav`. Instead render
  `RecordNavRail` (new — a vertical `Column` of two `NavItem`s, Library + Settings)
  placed via `placementFor(NAV_RAIL, LANDSCAPE)`, plus the existing `RecordFab`
  placed via `placementFor(RECORD_ACTION, LANDSCAPE)`.
- Extract `NavItem` (currently inline inside `RecordBottomNav`) and reuse the
  existing `RecordFab` as shared leaves. Both portrait bar and landscape rail/FAB
  assemble from the same leaves — no duplicated button logic.

### 4.4 Orientation-aware internal axis (two composables)

Two composables flip their internal stacking axis (not their slot — their content
direction), because a vertical stack reads badly in their landscape position:
- `RecordCameraControls`: portrait = vertical `Column` (flash above flip);
  landscape = horizontal `Row` (so it does not reach down toward the center FAB).
- `RecordActiveHud`: portrait = vertical `Column` (loop pill / status / bar);
  landscape = horizontal `Row` (loop + status side by side), within the 360dp cap.
  **Live-region semantics and the composable identity are unchanged** — only the
  inner `Arrangement`/axis differs.

Both take a `ChromeOrientation` (or a `Boolean isLandscape`) parameter. No new
behavior; pure layout.

### 4.5 Call-site mapping (Compose side, not unit-tested)

A `BoxScope` extension maps `SlotPlacement` → `Modifier`:

```kotlin
@Composable
fun BoxScope.slotModifier(p: SlotPlacement): Modifier {
    val alignment = when (p.anchor) {
        SlotAnchor.TOP_START    -> Alignment.TopStart
        SlotAnchor.TOP_END      -> Alignment.TopEnd
        SlotAnchor.TOP_CENTER   -> Alignment.TopCenter
        SlotAnchor.BOTTOM_CENTER-> Alignment.BottomCenter
        SlotAnchor.CENTER_START -> Alignment.CenterStart
        SlotAnchor.CENTER_END   -> Alignment.CenterEnd
    }
    var m = Modifier.align(alignment)
    m = when (p.anchor) {
        SlotAnchor.TOP_START     -> m.padding(start = p.crossInsetDp.dp, top = p.mainInsetDp.dp)
        SlotAnchor.TOP_END       -> m.padding(end = p.crossInsetDp.dp, top = p.mainInsetDp.dp)
        SlotAnchor.TOP_CENTER    -> m.padding(top = p.mainInsetDp.dp)
        SlotAnchor.BOTTOM_CENTER -> m.padding(bottom = p.mainInsetDp.dp)
        SlotAnchor.CENTER_START  -> m.padding(start = p.crossInsetDp.dp)
        SlotAnchor.CENTER_END    -> m.padding(end = p.crossInsetDp.dp)
    }
    if (p.maxWidthDp != null) m = m.widthIn(max = p.maxWidthDp.dp)
    return m
}
```

`RecordScreen` reads `ChromeOrientation` once at the root from
`LocalConfiguration.current.orientation` and threads it down. Each slotted region
becomes `Box(slotModifier(placementFor(SLOT, orientation))) { Region(...) }`.

## 5. Tokens

Landscape-specific dp values added to `RecordChromeTokens.kt` (same token-contract
convention; no magic numbers at call sites). New tokens (names indicative):
`navRailEdgeInset = 12`, `recordActionEdgeInset = 14`, `landscapeCardMaxWidth = 360`,
`landscapeHudMaxWidth = 360`, `landscapeTopStatusInset = 16`,
`landscapeRecoveryInset = 54`, `landscapeWarningInset = 92`,
`landscapeCamControlInset = 12`, `landscapeCardBottomInset = 12`. Portrait values
reuse the existing tokens (`bottomNavClearance`, `settingsCardLift`, etc.); the
hardcoded 16/70/110 top offsets get promoted to tokens as part of routing them
through the helper.

## 6. Accessibility (ADR-0020 standing requirement)

- Active-HUD `liveRegion` stays on the **same composable** in both orientations
  (no relocation, no re-announcement regression).
- Touch targets unchanged and already passing: FAB 56dp, nav icon box 42dp,
  cam-control 30dp box with touch slack (≥24dp SC 2.5.8). Landscape does not
  shrink any target.
- Reading/focus order in landscape is sensible: status → (recovery/warning) →
  nav rail → record action → params card. Verify with a single semantics ordering
  pass; no content changes.
- No reduced-motion changes (no new animation introduced).

## 7. Testing

JVM unit tests only (house policy). New file
`ChromeSlotPlacementTest.kt`:

1. **Per-slot pin** — for every `ChromeSlot`, assert `placementFor(slot, PORTRAIT)`
   and `placementFor(slot, LANDSCAPE)` return the exact `SlotPlacement` from the
   §4.2 table (16 assertions). This pins the contract.
2. **Portrait regression pin** — a focused test naming the portrait values as the
   "today" baseline (STATUS top/cross = 16/16, RECOVERY 70/16, WARNING 110/16,
   CAM_CTRL 7/7, HUD top 16, PARAMS bottom 120 / maxW null) so a future edit that
   silently shifts portrait fails loudly.
3. **Anchor family invariants** — center-edge anchors ignore the unused inset
   (e.g. `TOP_CENTER`/`BOTTOM_CENTER` crossInset is `—`/0; `CENTER_*` mainInset is
   `—`/0), guarding the inset-semantics contract.

No instrumented/Compose UI test is added (chrome composition is exercised by the
existing Compose test scaffolding only).

## 8. Scope guards & non-goals

- **No new `check*` gate.** β introduces no new ADR invariant. The enum-collapse
  gates land in PR-γ. (Owner may veto; default is no gate.)
- **No new user-facing strings** expected — reuse existing Library / Settings /
  Start-Stop content descriptions. Any new content description → en + es.
- **DualShot / P+L preview untouched.** It is a preview-only special case; chrome
  is layout-neutral. The `DualPreviewZone` rendering branch is not modified.
- **Camera / rotation / service / recovery paths untouched.** Strictly additive
  to PR-α.
- **No manifest change**, no orientation-lock change.
- **SCHEMA_VERSION not bumped** (no manifest persistence change).
- **Glass roles unchanged** (ADR-0028). Landscape nav-rail items reuse the
  existing `NavItem` styling (rounded icon box); no new gradient-ramp bar in
  landscape.

## 9. Relationship to PR-γ

PR-γ collapses the Portrait/Landscape mode tabs into `Single` + an
`OrientationPolicy` picker. The picker replaces the **mode chip inside
`PARAMS_CARD`** — the slot β already makes landscape-aware. β therefore leaves γ a
finished slot to drop into, satisfying ADR-0029 §B ("slot-based β before
enum-collapse γ"). γ also adds the enum-collapse `check*` gates and the manifest
schema bump; none of that is in β.

## 10. File touch list

- **Create:** `ui/screens/chrome/ChromeSlotPlacement.kt` (pure helper + enums).
- **Create:** `RecordNavRail` composable (in `RecordChrome.kt` or a sibling).
- **Create test:** `ChromeSlotPlacementTest.kt`.
- **Modify:** `RecordScreen.kt` — read `ChromeOrientation` at root; wrap slotted
  regions in `Box(slotModifier(placementFor(...)))`; branch nav portrait-bar vs
  landscape-rail+FAB.
- **Modify:** `RecordChrome.kt` — extract `NavItem` leaf; add `ChromeOrientation`
  param to `RecordCameraControls` and `RecordActiveHud` for internal-axis flip;
  add `slotModifier` `BoxScope` extension.
- **Modify:** `RecordChromeTokens.kt` — add landscape dp tokens; promote the
  hardcoded 16/70/110 top offsets to tokens.

---

**Self-review:** placeholders — none. Internal consistency — portrait table
values match the verified "today" layout from the source map; nav decomposition
matches §3 zone map and the mockup. Scope — single plan, chrome-only. Ambiguity —
inset semantics per anchor family are explicitly tabled and tested.

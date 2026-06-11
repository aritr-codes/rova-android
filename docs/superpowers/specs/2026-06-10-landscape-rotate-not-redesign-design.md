# Landscape "Rotate, don't redesign" — Design Spec

**Status:** Proposed (owner-ratified direction 2026-06-10; supersedes ADR-0029 §B).
**Amended 2026-06-11** after the Phase-A on-device UX checkpoint (owner NO-GO on visual weight,
GO on topology) — see §11. Approved interactive mockup: `landscape_record_mockup.html` (repo root).
**Branch context:** off `feat/liquid-glass-landscape-chrome` (PR-β). Portrait β5b polish is the
source of truth and is retained; the bespoke §B landscape pieces are reverted and rebuilt.
**Reference:** the device's stock camera app in landscape (RZCYA1VBQ2H screenshots, 2026-06-10).

---

## 1. Problem

ADR-0029 §B built a *landscape-optimized* chrome: a grouped nav rail pinned to the system-nav
edge, a docked vertical config card (Clip/Repeats/Quality/Mode — Wait dropped), and a "standard
side sheet" (corner Done, 3-up compact steppers, height-fit ~560dp). Owner rejected this on
2026-06-10: it reads as **a different UI**, not the portrait UI rotated. The desired mental model
is native-camera parity:

> "This is the exact same camera UI, just rotated."

not

> "This is a separate landscape layout."

## 2. Principle (the one rule)

**Portrait is the source of truth. Landscape is the identical widget set — same sizes, spacing,
order, hierarchy, and interaction model — re-anchored by a deterministic 90° rotation of the
*chrome layer*, with every widget kept upright (rotation-aware placement, NOT pixel rotation).**

Corollary: there is no "landscape design." There is only "where does each portrait widget go when
the device turns, and which physical edge does the bottom-bar follow."

## 3. Implementation model: rotation-aware placement (model B)

Two candidates were weighed (codex thread 019eb267):

- **(A) Rigid-body rotation** — wrap chrome in `Modifier.graphicsLayer { rotationZ = ±90 }`.
  **Rejected.** Renders text / steppers / sheet rows sideways (head-tilt), and corrupts touch
  geometry + accessibility bounds. Only viable for icon-only HUDs.
- **(B) Rotation-aware placement** — keep each widget upright; re-anchor the bottom config-strip +
  nav-bar to the screen edge that maps to portrait-"bottom" after the device rotation, preserving
  relative order; counter-rotate only individual glyph icons if ever needed. **This is what native
  camera apps do. Adopted.**

The preview stays full-bleed and unrotated; only chrome moves.

## 4. The rotation mapping (load-bearing — fixes the "swapped" bug)

### 4.1 Reference handedness (owner decision: match native camera)

From the stock-camera landscape screenshot on RZCYA1VBQ2H: the control cluster sits on the
**right** edge, and the portrait bottom mode-strip `FUN · PORTRAIT · PHOTO · VIDEO · MORE`
(left→right) renders **bottom→top** (FUN at bottom, MORE at top). Therefore the reference rule is:

> **portrait-LEFT → landscape-BOTTOM**, cluster on the edge that is the rotated "bottom".

### 4.2 Pure mapping

Add a pure helper (JVM-testable, no Android types in the math):

```
enum class DeviceLandscape { RotatedCW, RotatedCCW }   // ROTATION_90 vs ROTATION_270

// Given the portrait bottom-bar order (left→right) and the landscape sense,
// return the cross-axis order (the order to lay out top→bottom on the rail).
fun railOrder(portraitLtoR: List<T>, sense: DeviceLandscape): List<T> =
    when (sense) {
        // Match native: portrait-left → bottom  ⇒ reading top→bottom is the REVERSE.
        DeviceLandscape.<sense matching the native screenshot> -> portraitLtoR.reversed()
        else -> portraitLtoR            // the mirror orientation
    }
```

- `[Library, FAB, Settings]` (L→R) ⇒ on the native-matching landscape, rail top→bottom =
  `[Settings, FAB, Library]` (Library at bottom). The two landscape orientations are **mirror
  images** of each other (ROTATION_90 ↔ ROTATION_270 produce opposite vertical ordering).
- The same mapping applies to the config strip cells
  `[Clip, Repeats, Wait, Quality, Mode]`.

### 4.3 On-device verification (mandatory acceptance)

The exact `Surface.ROTATION_*` ↔ `DeviceLandscape` binding must be confirmed against the stock
camera on a real device (emulators mis-handle sensor rotation). Acceptance: rotate the phone both
ways; in each, Rova's rail order and cluster edge match the stock camera's controls handedness.

## 5. Per-component spec

All sizes/spacing/styling are **inherited from portrait tokens** (`RecordChromeTokens`,
`SettingsSheetTokens`). Landscape introduces no new visual proportions.

| Component | Landscape placement |
|-----------|---------------------|
| **Nav bar** (Library · FAB · Settings) | Vertical rail on the rotated-bottom edge. Order from §4.2 (fixes the swap). Same FAB size, same inter-item spacing as the portrait bottom bar. Icons upright. |
| **Config strip** | All **5** cells restored (Clip · Repeats · **Wait** · Quality · Mode). ~~Same pill styling/size/spacing~~ **Compact density in landscape (§11 amendment):** same component, same order/anchoring, but slimmer cells — reduced padding + type scale, value + small caps label — so the vertical column does not visually dominate the rail (the Phase-A NO-GO). Portrait keeps full density. Sits adjacent to the rail exactly as it sits *above* nav in portrait. Cells upright. "Swipe to edit" affordance retained. |
| **Status chip** | Rotated-top-leading. |
| **Flash / lens** | Rotated-top-trailing. |
| **Settings sheet** | Same component: grip + centered "Recording settings", **stacked full-width** Clip/Repeats/Wait/Quality rows, Built-in/My-presets split, summary line, Save CTA. Slides in from the rotated-bottom edge (§5.2). Same sizing/spacing/density as portrait, and the **same perceived visual weight** (§5.1). Same **scrim/preview treatment** as portrait (§6.6). **Vertical scroll** when landscape height can't fit it all (owner-accepted — §6.1). NO 3-up, NO corner-Done, NO height-fit card, NO desktop dialog. |

### 5.1 Sheet visual weight & width (owner requirement 2026-06-10)

The earlier landscape sheet failed not only on height but on **perceived weight**: a narrow 380dp
card reads as smaller and weaker than its portrait counterpart. Requirement: the landscape sheet
must carry **approximately the same perceived visual weight and screen-proportion as portrait**, not
become a compact desktop-style card.

**SUPERSEDED 2026-06-11 (§11).** The derivation below was built in Phase A
(`width = availableWidth − peekHeight`, ≈1780px) and **rejected at the on-device checkpoint**:
owner — "the width feels excessive, the Save CTA is oversized, reads like a desktop panel."
The lesson: portrait's *proportion* does not transfer across the aspect-ratio swap; what transfers
is portrait's *silhouette*. A sheet that is phone-portrait-wide reads as "my settings sheet" in any
orientation; a sheet that is ~76% of a 2.16:1 width reads as a new desktop surface.

> **Amended rule: landscape sheet width = `SettingsSheetTokens.sideSheetWidth` (380dp, the
> portrait-silhouette cap), full height, never exceeding ~47% of the window width.**
> Save CTA uses a slimmed vertical padding in landscape (`ctaPaddingV` 16 → 12dp) so it scales with
> the narrower panel; all other internal sizes stay at portrait token values. Body scrolls (§6.1) —
> Repeats/Wait/Quality/Save must be reachable below the fold (verified at the checkpoint as a risk).

(Original Phase-A derivation, kept for history: the portrait sheet leaves a `peekHeight` (~212dp)
live-camera peek strip and fills the rest; rotating that rigidly gives
`width = availableWidth − peekHeight`. Geometrically faithful, perceptually wrong — too wide.
`sideSheetMinWidth = 360.dp` from that scheme is retired with it; `sideSheetWidth = 380.dp`
is un-retired and becomes the cap.)

### 5.2 Why side-anchored, not a centered modal (rationale — owner asked)

The principle is "preserve the portrait *experience*," and the portrait sheet is **edge-anchored**:
it grows from the bottom edge — the very edge where the Settings button lives — covering the bottom
config+nav cluster while the camera peek stays above. The spatial contract the user learns is *"tap
Settings, the sheet rises from where I tapped."*

A faithful rotation preserves that contract: in landscape the Settings button + cluster sit on the
rotated-bottom edge, so the sheet should grow **from that same edge**, covering the cluster, with the
preview peek filling the rest. That is side-anchored — and it is *more* "rotated portrait," not less.

A **centered modal/card** would actually break the rotate-don't-redesign principle: portrait's sheet
is not centered, so centering introduces a new spatial model (a floating dialog with no edge
relationship to the control that summoned it) — precisely the "separate landscape layout / desktop
dialog" feeling we're eliminating. Centered also tends to invite the compact-card proportions that
were rejected.

**Decision: side-anchored from the rotated-bottom (cluster/Settings-button) edge, at full portrait
visual weight (§5.1) and with the portrait scrim (§6.6).** The earlier rejection was of a *compact,
re-styled* side panel — not of edge-anchoring itself. Centered remains a low-cost fallback if, on
device, side-anchored still reads wrong; the plan keeps the container swappable.

## 6. Technical constraints (codex-validated, thread 019eb267)

### 6.1 Scroll is the honest fallback, not a compromise
Landscape height (~1080px) < portrait sheet content (~1400px+). Keeping portrait sizing + one
column ⇒ overflow ⇒ scroll, exactly as a short portrait phone scrolls. Multi-column is unjustified
below 840dp (phones aren't) and would itself be "a different adaptive layout." Owner ratified:
**same sheet, scroll overflow.**

### 6.2 VideoCapture.targetRotation MUST track device rotation (correctness)
Preview can look correct while the recorded MP4 is mis-oriented. The PR-α `effectiveTargetRotation`
+ `OrientationEventListener` seam already exists for this and MUST keep being fed by the new chrome.
This is the one genuinely load-bearing correctness item — re-verify after the rework.

### 6.3 No Activity recreate
`configChanges` makes rotation a pure recompose. `PreviewView` may relayout / recreate its internal
surface — normal, not an Activity recreate. Keep preview full-bleed + unrotated; move chrome only.

### 6.4 Side-anchored sheet container
Compose's bottom-sheet is a bottom-edge primitive; landscape needs a custom side-anchored container
that renders the **same** content composition. Container swap, not a redesign.

### 6.5 No graphicsLayer on interactive chrome
Per §3, placement-aware only — keeps touch + a11y geometry honest.

### 6.6 Scrim / preview-visibility parity (owner requirement 2026-06-10)
Landscape must use the **same scrim and preview-visibility treatment as portrait** — no extra
darkening, no heavier modal feel. Portrait reuses the camera **peek scrim**
(`SettingsSheetTokens.peekScrim*` gradient) behind/around the sheet so the live preview stays
visible; landscape reuses that identical treatment in the rotated layout. Specifically: do NOT add a
full-screen dim/scrim behind the landscape sheet, and do NOT reduce preview visibility relative to
portrait. The previously-shipped landscape side panel was *non-modal with no scrim* (so the rail
stayed visible); the rotated sheet should likewise avoid introducing modal darkening — match
whatever portrait does, no more.

## 7. ADR + gate impact

- **Amend ADR-0029:** mark §B (slot-based landscape) withdrawn; add **§B′ "Rotate, don't
  redesign"** carrying §2's principle + §4's mapping rule + §6.2 correctness note.
- No `check*` gate is affected (this is layout). If a new invariant emerges (e.g. "landscape rail
  order derives from the pure mapping, never declaration order"), follow the
  invariant → `check*` → `preBuild` convention.

## 8. Migration impact

- **Kept:** PR-α orientation sampling/seam (`effectiveTargetRotation`, `OrientationEventListener`);
  **all portrait β5b polish** (approved); `systemNavEdge` (still identifies the nav edge).
- **Reverted + rebuilt:** `ChromeSlotPlacement` landscape zones; `RecordConfigCardLandscape`
  (docked card); `SettingsSidePanel` height-fit + `CompactSteppers` 3-up; declaration-order
  `RecordNavRail`.

## 9. Acceptance criteria

0. **UX-first (the core outcome):** a user fluent in portrait can operate landscape **without
   relearning control locations or interaction patterns** — every control is where the rotated
   portrait layout puts it, and behaves identically. This is the criterion all others serve.
1. Rotating the device feels like the portrait UI turned — no element is re-conceived.
2. Nav order + cluster edge **match the stock camera** in both landscape orientations (mirrored).
3. Config strip shows all 5 cells; "Wait" no longer dropped.
4. Settings sheet is the portrait sheet (grip, title, stacked rows, Save), scrolling when needed —
   not a compact/desktop variant.
5. **Sheet silhouette (amended §11):** the landscape sheet is the portrait sheet's silhouette —
   `sideSheetWidth` (380dp) cap, full height, slimmed Save — NOT a ~70%-width panel (rejected at
   checkpoint as "desktop"). Repeats/Wait/Quality/Save reachable by scroll.
6. **Scrim parity:** landscape uses the same scrim + preview visibility as portrait — no added
   darkening or heavier modal feel (§6.6).
7. Recorded MP4 orientation is correct in both landscape senses (§6.2).
8. Portrait is byte-for-byte unchanged.

## 10. Open questions deferred to the plan

- Exact `Surface.ROTATION_*` ↔ `DeviceLandscape` binding (resolve via §4.3 on-device probe).
- Whether the config strip + nav share one rotated container or two (portrait has them as
  separate bottom layers; mirror that).

## 11. Amendment — 2026-06-11 UX checkpoint (Phase A NO-GO → weight fixes)

Phase A (commit `838d279`) implemented §§2–6 faithfully and passed build + both-sense device
screenshots. At the A6 checkpoint the owner ruled **NO-GO on visual weight, GO on topology**:

1. *"The vertical config strip is too dominant — a tall tower competing with the rail and FAB."*
2. *"The layout feels sparse / not intentional."*
3. *"The sheet width feels excessive, the Save CTA oversized — reads like a desktop panel."*
4. *"The nav rail itself is largely fine."*

A three-zone alternative (top-center config dock, round-3 style) was explored, mocked, and
**explicitly rejected** by the owner: *"rotate the chrome, NOT redesign the chrome — no dedicated
landscape information architecture."* The native stock camera confirms the rotate model including
order reversal (its portrait icon row becomes a reversed right-side column in landscape).

**Resolution (owner-approved via `landscape_record_mockup.html`):** topology is unchanged from
Phase A; only *density* changes —

| Delta | Spec clause touched |
|-------|---------------------|
| D1 — Config strip renders **compact density** in landscape (slimmer padding/type; value + small caps label). Portrait full density untouched. | §5 table |
| D2 — Sheet width capped at `sideSheetWidth` (380dp, ≤~47% window); the `availableWidth − peekHeight` derivation is superseded. | §5.1 |
| D3 — Save CTA slimmed in landscape (`ctaPaddingV` 16 → 12dp); sheet body scroll verified to reach Repeats/Wait/Quality/Save. | §5.1, acceptance 5 |
| D4 — Status chip + flash/lens are **screen-anchored** (top-start / top-end in BOTH senses, like native indicators); only the cluster + sheet are device-anchored. Confirms Phase-A behavior as intended. | §5 table |
| D5 — Sparse preview area is accepted as native-normal ("do not optimize for empty space" — owner). | §1 framing |

Item 2 (sparseness) is resolved by D1+D5: with the tower slimmed, the empty preview reads as
native-camera, which the owner ratified by approving the mockup.

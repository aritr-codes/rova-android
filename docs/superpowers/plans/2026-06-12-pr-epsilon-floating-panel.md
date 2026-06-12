# PR-ε addendum — floating settings panel (owner-ratified V1×V3 hybrid)

> Executed on `feat/pr-epsilon-fixed-window-chrome` after the T10 smoke-fix batch.
> Owner verdict 2026-06-12: smoke finding #1+#5 — unlock-while-open REVERSED; sheet
> replaced by a floating panel. Mockup `floating_panel_mockup.html`: **V1 content
> (full-label stepper rows, no scrim, Presets… collapsed) in V3 geometry (near-square
> centered card, rotation-invariant footprint, spins as one unit)**.

## Decisions (ratified)

1. **Window stays locked, always, on compact.** `modalOpen` no longer feeds the lock —
   on FixedPhysical the lock is `route ∧ FixedPhysical`. The panel is record-chrome,
   not a window surface; it counter-rotates via `SpinningBox` like everything else.
   ADR-0029 §B″5 to be rewritten (unlock-while-open clause dies).
2. **Presentation by chrome mode:** FixedPhysical → new `FloatingSettingsPanel`
   centered over the viewfinder, no scrim, tap-outside dismisses, whole card spins
   (`SpinningBox(degrees = spinDegrees)`). Adaptive (sw600dp+) → existing
   bottom-sheet/side-panel unchanged.
3. **Geometry:** card width ≈ 320dp (capped `min(parentWidth − 44dp, 320dp)`),
   height wrap-content but visually near-square; centered horizontally, anchored
   upper-center (~18% from top) so the strip/nav stay visible below.
4. **Content (V1 set):** Auto/DualShot segmented tabs · Clip length / Clips /
   Wait between stepper rows · Quality selector row · Orientation row ·
   `Presets…` collapsed row that expands the preset chips inline. Save semantics
   identical to the current sheet (reuse its state plumbing — same ViewModel calls,
   same `combinedSettingsOpen` visibility flag).
5. **Thermal tips sheet:** unchanged ModalBottomSheet; renders portrait under the
   permanent lock. Follow-up candidate, not in scope.
6. **Animations:** open/close fade+scale gated on `rememberReduceMotion()`
   (`checkA11yAnimationGated`).
7. **Gates:** no gate changes needed; `checkRecordChromeLockSingleSite` still holds
   (lock site unchanged, only its inputs simplify).

## Tasks

- P1: `FloatingSettingsPanel` composable (new file `ui/screens/FloatingSettingsPanel.kt`),
  reusing SettingsSheet's row internals where extractable; RecordScreen presentation
  branch (FixedPhysical panel / Adaptive sheet); lock simplification; ADR §B″5 rewrite.
- P2: controller build + owner re-smoke (panel open/close both grips, steppers work,
  presets expand, orientation row, tap-outside, TalkBack pass).

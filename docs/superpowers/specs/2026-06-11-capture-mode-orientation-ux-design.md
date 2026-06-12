# Capture-Mode + Orientation UX — Design Spec

**Date:** 2026-06-11
**Status:** **Approved** (owner, 2026-06-11) — including §5 mode-cell visibility: owner ratified Variant A (Auto always visible, quiet; accent only when mode ≠ Auto) after native-UX review (`config_strip_mode_mockup.html`, codex concur)
**Relationship:** This is the **user-facing layer of ADR-0029 PR-γ** (capture-topology / orientation-policy enum collapse + migration). The internal two-axis model, rotation semantics, merge contract, and capability gating are specced in `docs/adr/0029-capture-topology-orientation-policy.md` and are NOT re-specced here. This document specs what users see: mode picker, config card, settings sheet, orientation controls, terminology, and migration UX.
**Peer review:** codex-reviewed 2026-06-11 (endorsed Option-1 orientation demotion; naming + locked-state-visibility + caption guidance folded in). Owner overrode codex on one point: mode name is **Auto**, not "Standard".

---

## 1. Problem

The mode picker currently exposes **Portrait / Landscape / P + L**. Since PR-α shipped device-driven orientation (`effectiveTargetRotation` + OrientationEventListener), Portrait and Landscape are functionally identical — orientation is automatic. The UI is treating **device orientation as a mode**, which:

- makes the most prominent config-card element (saturated accent mode chip) communicate something users should not manage manually;
- blocks the roadmap: real future modes (DualSight/FrontBack PiP) have no coherent home in an orientation-named picker;
- fragments terminology: HUD says "loops", config says "repeats", notifications say "clips", legacy strings say "segments".

## 2. Principles (owner-ratified)

1. Orientation handling is automatic. Orientation is **not** a user-facing mode.
2. **Mode = capture behavior** (what happens when you press record), not screen orientation.
3. The config card answers *"what will happen when I press record?"*
4. Orientation lock remains available (mounted-recorder workflows, ADR-0029 Ratified-A) but **demoted** to a quiet settings-sheet row — never in the mode picker.
5. One user-facing vocabulary: **Session** (the whole run) and **Clip** (one video). "Loop", "repeat", "segment" are banned from user copy (internal code keeps them).
6. UX moves closer to a native camera app.

## 3. Mode structure

| Mode (user label) | Internal (`CaptureTopology`) | Behavior | Picker visibility |
|---|---|---|---|
| **Auto** | `Single` | One camera, one output per clip, orientation automatic | Always |
| **DualShot** | `DualShot` | One rear sensor → portrait + landscape outputs from the same session (ADR-0009) | Always; rear-only (lens flip disabled, per ADR-0009 / B6) |
| **DualSight** | `FrontBack` | Rear full-frame + front-camera PiP in one file (ADR-0029 Ratified-C) | **Hidden** unless the device reports concurrent front+rear support (`getConcurrentCameraIds()` combination check — capability-gated, never API-level-gated; ADR-0029 §5) |

- Mode surfaces: settings-sheet **CAPTURE MODE** tabs + record-home **ModeCycleChip**. Both render from the same mode list so a future mode is one registry entry, no layout work.
- Each tab carries a one-line caption (names alone don't carry consequences — codex):
  - Auto: *"Records clips, rotates with your device"*
  - DualShot: *"Every clip saved as portrait + landscape (2× storage)"*
  - DualSight: *"Front + rear cameras in one video"*
- On hardware without concurrent support, DualSight is absent from the picker (not greyed). A one-line capability note in the main Settings screen (About/diagnostics area) explains availability, so external references to DualSight don't read as a missing feature. Exact placement decided at plan time; the note is non-blocking copy, not a control.
- **DualSight in this spec is picker-model only** (name, gate, caption). The feature itself is PR-δ and is NOT in scope here.

## 4. Orientation policy UX

Internal model unchanged (ADR-0029 Ratified-D: locks persist an explicit `Surface.ROTATION_*`).

User-facing values:

| Label | Internal | Default |
|---|---|---|
| **Follow Device** | `Auto` policy | ✔ (new users + new sessions) |
| **Lock Portrait** | lock @ portrait rotation | |
| **Lock Landscape** | lock @ landscape rotation | |

Rules:
1. Orientation appears in exactly one control: a quiet **ORIENTATION** row in the settings sheet (3-way segmented control, not accent-painted). This satisfies ADR-0029 Ratified-A's "control visible pre-record" condition — the sheet is the pre-record config surface.
2. **Locked state is loud; default state is invisible.** When policy ≠ Follow Device, a quiet config-card cell appears (e.g. `LOCKED · PORTRAIT`). When Follow Device (the normal case), orientation has zero pre-record footprint. This is the codex-sharpened visibility rule: a buried active lock would be mysterious stale state, especially post-migration.
3. The word "Auto" is reserved for the **mode**. The orientation policy never displays "Auto" — always "Follow Device" — so the two concepts can't collide in one sheet.

## 5. Config card

Content (left→right in portrait; same cells rotated per landscape chrome):

```
5s LENGTH · 2 CLIPS · None WAIT · FHD QUALITY · [Auto]
5s LENGTH · 2 CLIPS · None WAIT · FHD QUALITY · [DualShot]          ← accented
5s LENGTH · 2 CLIPS · None WAIT · FHD QUALITY · [Auto] · LOCKED·PORTRAIT
```

**Hierarchy rule (the load-bearing change):** the mode chip paints the accent gradient **only when mode ≠ Auto**. Auto renders as a quiet cell like its neighbors. Accent = "something non-standard happens when you press record" — a real signal, not decoration.

Consequences:
- Common-case idle (Auto) has **no saturated chip**, restoring the record FAB as the visual top of hierarchy. (The separate FAB-token A/B decision — `fab_comparison_mockup.html` — remains open and independent; this change removes its main competitor in the common case.)
- The PR2-§d accent theme anchor survives, scoped to the modes that earn it (DualShot/DualSight). The white-on-gradient ADR-0020 contrast exemption continues to apply to the accented state only.
- The `LOCKED · <ORIENTATION>` cell appears iff policy ≠ Follow Device; quiet styling (standard cell tokens), placed at the strip end so the steady-state strip is unchanged.

## 6. Settings sheet organization

```
CAPTURE MODE    [ Auto | DualShot | (DualSight) ]   ← tabs + active-mode caption line
SESSION         Clip length · Clips · Wait between  ← existing steppers, renamed labels
OUTPUT          Quality
ORIENTATION     [ Follow Device | Lock Portrait | Lock Landscape ]  ← quiet row, last
```

- Same `SettingsBottomSheet` (portrait) / `SettingsSidePanel` (landscape) chrome; this is section/label reorganization, not new sheet architecture.
- `SheetModeTab` enum (`SettingsSheet.kt:722`) collapses from Portrait/Landscape/PortraitLandscape to the mode registry above.
- Caption line sits under the tabs and reflects the active tab (the three captions in §3).

## 7. Terminology system

Two user-facing nouns: **Session** = one Start→Stop run; **Clip** = one video within it.
Banned from user copy: *loop, repeat, segment*; *portrait/landscape as mode names* (fine as plain descriptive words, e.g. DualShot's caption).

### Rename table (en; es gets matching twins — "DualShot"/"DualSight" stay unlocalized brand names)

| Resource | Current | New |
|---|---|---|
| `record_loops_done_caption` | LOOPS DONE | CLIPS DONE |
| `record_hud_loops_done` | %1$d/%2$d loops done | %1$d/%2$d clips done |
| `record_hud_loops_done_indefinite` | %1$d loops done | %1$d clips done |
| `record_hud_loops_remaining` | %1$d of %2$d loops remaining | %1$d of %2$d clips remaining |
| `record_cell_clip` | Clip | Length |
| `record_cell_repeats` | Repeats | Clips |
| `settings_sheet_clip_duration` | Clip Duration | Clip length |
| `settings_sheet_repeats` | Repeats | Clips |
| `settings_clip_duration_label` | Clip duration | Clip length |
| `settings_loops_label` | Number of loops | Number of clips |
| `settings_loops_supporting` | How many clips before stopping. | (unchanged — already "clips") |
| `history_config_repeats` | Repeats | Clips |
| `settings_summary_repeats` | ×%1$d | (unchanged — symbol, no vocabulary) |
| `settings_repeats_fixed*` / `settings_repeats_continuous*` | Fixed / Continuous (+CDs) | (values unchanged — "Fixed/Continuous" carry no banned noun; resource *names* keep `repeats`, internal) |
| `notification_segment_saved` | Segment Saved: %1$d KB | Clip saved (%1$d KB) |
| `notification_empty_segment` | Recording produced an empty segment | Recording produced an empty clip |
| `record_mode_pl_label` | P + L (es: V + H) | DualShot (both locales) |
| `settings_sheet_mode_portrait` | Portrait | **deleted** |
| `settings_sheet_mode_landscape` | Landscape | **deleted** |
| `settings_sheet_mode_pl` | P + L | replaced by mode-registry labels (Auto / DualShot / DualSight) |
| es `record_loops_done_caption` | CICLOS HECHOS | CLIPS HECHOS |

New strings: mode labels (`record_mode_auto` etc.), three mode captions, orientation row labels (Follow Device / Lock Portrait / Lock Landscape + CDs), `LOCKED · PORTRAIT/LANDSCAPE` cell text — exact resource names assigned at plan time; all en+es per `checkNoHardcodedUiStrings`.

Already-conformant surfaces (unchanged): live notifications ("Recording · Clip 1 of 2"), player ("Clip n of m"), merge HUD, onboarding, channel names ("Recording session").

**Internal code is untouched**: `loopCount`, `Segment*` classes, segment file naming, and the gates that depend on them (`checkRecoverySegmentRegex` etc.) keep their vocabulary. Zero Kotlin renames for terminology.

**Candidate future gate** (proposed, not required for this delivery): a `check*` scan asserting no user-visible string in `values*/strings.xml` contains `loop|repeat|segment` outside an allowlist — follows the ADR-clause → check-task → preBuild convention. Decide at plan time whether it ships with PR-γ or later.

## 8. Migration UX

Persisted `mode` string today: `"Portrait" | "Landscape" | "PortraitLandscape"`. PR-γ migration (mechanics per ADR-0029; this section specs the *user-visible* result):

| Old mode | New topology | New orientation policy | What the user sees |
|---|---|---|---|
| Portrait | Auto (`Single`) | **Lock Portrait** | Mode chip "Auto" + `LOCKED · PORTRAIT` cell — behavior preserved (Ratified-A), lock visibly disclosed |
| Landscape | Auto (`Single`) | **Lock Landscape** | Mode chip "Auto" + `LOCKED · LANDSCAPE` cell |
| PortraitLandscape | DualShot | (per ADR §4 — DualShot owns rotation) | Mode chip "DualShot" (accented), behavior unchanged |

The `LOCKED` cell is the disclosure mechanism — no migration dialog/toast. A migrated user who wants rotation taps Settings → Orientation → Follow Device.

## 9. Out of scope

- DualSight implementation (PR-δ; ADR-0029 Ratified-C owns its design — history grouping of dual outputs, storage estimates, capability-loss fallback messaging are δ-design items per codex).
- FAB idle-prominence tokens (open A/B via `fab_comparison_mockup.html`, separate owner decision).
- Recording-HUD geometry anomaly trace (separate open item).
- Backend enum/migration/gate mechanics (ADR-0029 PR-γ scope — the plan implements both together, but ADR is the source of truth for the model).
- Advanced locks (reverse/180°) — UI-only later add per Ratified-D.

## 10. Acceptance criteria

1. Mode picker (sheet tabs + cycle chip) shows exactly Auto / DualShot, plus DualSight only on capability-reporting hardware. No Portrait/Landscape tabs anywhere.
2. Config card cells: LENGTH · CLIPS · WAIT · QUALITY · MODE. Mode chip accent-painted iff mode ≠ Auto.
3. `LOCKED · <ORIENTATION>` cell present iff orientation policy ≠ Follow Device; absent otherwise.
4. Settings sheet has the ORIENTATION row (Follow Device default); orientation appears nowhere else.
5. The string "Auto" never appears as an orientation value; "Follow Device" never appears as a mode.
6. No user-visible copy contains "loop", "repeat", or "segment" (en + es; rename table §7 applied).
7. Migration mapping per §8; migrated locks visibly disclosed via the LOCKED cell.
8. All existing invariants hold: ADR-0009 DualShot rotation contract, ADR-0020 contrast (gradient exemption scoped to accented chip), `checkNoHardcodedUiStrings`, portrait/landscape chrome slot behavior from the current branch.

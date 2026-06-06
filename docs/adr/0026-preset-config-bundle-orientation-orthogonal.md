# ADR-0026: Preset = config bundle; orientation is orthogonal; built-ins are read-only

Status: Accepted
Date: 2026-06-06

## Context

The Record screen exposes four recording controls (clip duration, interval,
repeats/loop-count, quality) plus an independent orientation control
(Portrait / Landscape / P+L). The "Mode Preset Seed" feature introduces named
presets. The original mockup conflated "mode" (orientation) with preset
("config bundle"), which would make a single word mean two things on one screen.

## Decision

1. A **preset carries only** `{duration, interval, loopCount, resolution}`.
   It MUST NOT carry orientation/mode. Applying a preset never changes the
   camera orientation.
2. **Built-in presets are code-defined and read-only** (`object BuiltInPresets`),
   never persisted. They can be retuned in a future release and reach all users.
   User-saved customs persist in `RovaSettings.customPresetsJson`.
3. Preset persistence has its own `presetSchemaVersion` envelope and is fully
   independent of `SessionManifest.SCHEMA_VERSION` (no manifest bump).

## Enforcement

`checkPresetNoOrientation` (Gradle, wired to `preBuild`) fails the build if
`RovaPreset` or `BuiltInPresets` gains an orientation/mode field.

## Consequences

- "Active preset" is determined by *value match* against built-ins, not identity;
  a custom whose values equal a built-in displays as that built-in. This is intended.
- Future per-clip "phases" land as additive optional fields under the same
  envelope, no corner.
- Built-in preset **names** ("Quick Sample", "Standard", "Long Session",
  "Continuous") are intentionally English/code-defined in v1 and surface as chip
  labels via `Text(p.name)` (so the no-hardcoded-strings gate does not apply).
  Their TalkBack content descriptions ARE localized (en/es, ADR-0022/0023).
  Per-locale built-in *name* overrides are a deferred follow-up, not a v1 gap.

## Amendment 2026-06-06 — custom preset save/naming

Custom presets are now user-creatable from the Record settings sheet:

- **Save affordance.** A "+ Save" chip appears in the PRESETS FlowRow **only when
  `activePresetId == null`** (the current config matches no preset = "Custom")
  **and the sheet is editable** (no active/merging session). It opens a naming
  dialog.
- **Name + tuple uniqueness.** A custom name must be non-blank, ≤ 40 chars, and
  unique case-insensitively against both built-in and existing custom names
  (`PresetSaveValidator`). A config whose tuple already matches any preset cannot
  be saved (guarded in `savePreset`; unreachable via the conditional chip).
- **Active reflection.** `activePresetId` now reflects customs via
  `PresetMatcher.matchActive` — **built-in value-match takes precedence**, then
  the first custom value-match.
- **Delete.** Custom chips delete via long-press → confirm dialog. The long-press
  is exposed to TalkBack/Switch/Voice as a labelled custom action
  (`onLongClickLabel`), satisfying WCAG SC 2.5.1 / 2.1.1 without a visible ✕.
  Built-in chips are not deletable.
- **Mid-session guards.** `savePreset`/`deletePreset` no-op while a session is
  active or merging (VM-side, mirroring `applyPreset`).

No new `check*` gate: the slice adds no statically-scannable invariant beyond
orientation-orthogonality, which `checkPresetNoOrientation` already enforces via
the unchanged `RovaPreset` / `BuiltInPresets` shape. Name/tuple uniqueness and
active-reflection are runtime behaviors covered by `PresetSaveValidatorTest` and
`PresetMatcherTest`.

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

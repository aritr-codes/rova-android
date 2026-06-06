# Mode Preset Seed — Design

**Date:** 2026-06-06
**Status:** Approved (brainstorming) — pending implementation plan
**Backlog ref:** `NEW_UI_BACKEND_REPLAN.md` §Remaining backlog #1 ("Mode preset seed")
**ADR:** introduces **ADR-0026** + static-check gate `checkPresetNoOrientation`

---

## 1. Problem & goal

Rova exposes recording config as four steppers (clip duration, interval, repeats/loop-count,
quality) with no fast path for a non-technical user to get a sensible configuration in one tap.
The original mockup (`mockups/new_uiux/01-home-idle.html`) sketched persona-named tabs
("Drill" / "Vlog") *next to* the orientation "Mode" cell — baking in a vocabulary collision
where "mode" means both **orientation** and **config bundle**.

**Goal:** ship a small set of well-named built-in presets as the *progressive-disclosure spine*
of the Record idle screen — one tap applies a whole config bundle; the steppers move behind a
"Customize" door. Simple for beginners, non-destructive for existing users, no new behavioral
surprises.

Research basis (verified, see session deep-research 2026-06-06): progressive disclosure
(preset-first, advanced-hidden) is the proven mechanic; the dominant failure is over-hiding a
commonly-needed control; persona naming presumes the user's scenario and ages badly; keep the
preset count small (3–5).

## 2. Core model (decision: "Model A")

A **preset is a bundle of `{clip duration, interval, repeats, quality}` only.**
**Orientation** (Portrait / Landscape / P+L) is a **separate, orthogonal control** that persists
independently and is **never** carried by a preset. Switching preset never changes the camera
orientation.

This permanently resolves the "mode" collision: *preset* and *orientation* are distinct axes.
Enforced by **ADR-0026** + `checkPresetNoOrientation` (a `RovaPreset` carrying an
orientation/mode field fails the build).

**Non-goals (YAGNI):** no orientation in presets; no flexible per-clip "phases" (deferred — the
JSON schema is left additive-ready so phases can land later without a corner); no user-editable
built-ins; **no `SessionManifest.SCHEMA_VERSION` bump** (presets live in `RovaSettings`, not the
manifest); no service / scheduler / recovery-classifier change.

## 3. Built-in preset set

Four code-defined built-ins. Outcome/cadence-named (no persona assumption). Values are
**beta-tunable constants** — the structure ships now; exact numbers are validated later with one-
line edits.

| id (namespaced) | Display name | Clip | Interval | Repeats | Quality |
|---|---|---|---|---|---|
| `builtin.quick_sample` | Quick Sample | 10s | 1m | 10× | FHD |
| `builtin.standard` | Standard | 30s | 2m | 20× | FHD |
| `builtin.long_session` | Long Session | 60s | 5m | 50× | HD |
| `builtin.continuous` | Continuous | 60s | 0 (no gap) | ∞ (`loopCount=-1`) | HD |

`Standard` is the first-run default (see §7).

**Invariant:** built-in value tuples MUST be pairwise-distinct (codex #3) — otherwise
"modified → Custom" classification is unobservable from values alone. Enforced by a unit test.
All four above are distinct.

## 4. Components

### 4.1 `data/BuiltInPresets.kt` (new)
`object BuiltInPresets` exposing `val all: List<RovaPreset>` — the four entries above, read-only,
each with a stable `builtin.*` id and `isBuiltIn = true`. Values are top-of-file constants.
Never persisted.

### 4.2 `RovaPreset` (extend — `data/RovaSettings.kt`)
Add:
- `id: String` — stable identity.
- `isBuiltIn: Boolean = false` — **runtime tag only**, not persisted (built-ins are never written;
  customs always deserialize to `false`).

Unchanged fields: `name, duration, interval, loopCount, resolution`.

### 4.3 Persistence schema (`customPresetsJson`)
**Customs only** are persisted (built-ins are code). Reader is additive-tolerant and branches on
root token (codex #5):

- **Legacy root = `JSONArray`** (today's `"[]"` / array-of-objects): read each object; missing `id`
  → derive `custom.<stableHashOfValues>`; treat as `presetSchemaVersion = 1`.
- **New root = `JSONObject` envelope**: `{ "presetSchemaVersion": 2, "presets": [ {id,name,duration,
  interval,loopCount,resolution}, ... ] }`.
- **Unknown object fields skipped; a single malformed preset object is skipped, NOT the whole list**
  (today's `loadPresetsFromSettings` drops the entire list on any parse error — this is tightened to
  per-object try/skip).
- **Reserved-prefix guard:** any loaded custom whose `id` starts with `builtin.` is re-namespaced to
  `custom.*` on read (a custom can never impersonate a built-in — codex #4).

Writer always emits the new envelope (`presetSchemaVersion = 2`) with `custom.*` ids.

### 4.4 `PresetMatcher` (new pure helper)
`fun match(duration, interval, loopCount, resolution): String?` → the `builtin.*` id whose tuple
equals the current config, else `null` ("Custom"). Pure → JVM-testable.

- Resolution compared via `QualityPresets.canonicalize(...)` so legacy aliases (`1080p`→FHD,
  `UHD`→4K) match correctly (codex #7); built-ins store canonical labels.
- `loopCount` compared exactly, including the `-1` continuous sentinel (codex #6).
- **Built-in wins over an identical custom** — "active preset = built-in value match, else Custom."
  This is *semantic* selection by config, not identity. Documented as intended (codex #2). If custom
  identity is ever needed in UI, add a separate `lastAppliedPresetId` later — it must NOT become the
  recording source of truth.

### 4.5 `RecordViewModel` (extend)
- `fun applyPreset(p: RovaPreset)` — sets the four existing `StateFlow`s
  (`duration/interval/loopCount/resolution`); the existing persist-on-change writes to `RovaSettings`.
  Does **not** touch orientation.
- `val activePresetId: StateFlow<String?>` — derived from the four config flows via `PresetMatcher`
  (`combine`); `null` renders as "Custom".
- `val allPresets: List<RovaPreset>` = `BuiltInPresets.all + customs`.
- Existing `savePreset` / `deletePreset` / load / persist retained (writer now emits envelope + ids).

### 4.6 UI (Record idle screen)
- **Preset chip row** + a **read-only summary line** of current values (`30s · 2m · 20× · FHD`).
- Active chip highlighted from `activePresetId`; "Custom" chip/label when `null`.
- **"Customize"** (tapping the summary) opens the **existing `SettingsStepperSheet`** as the
  advanced door — minimal new UI, just reparented.

## 5. Data flow

```
tap preset ─▶ applyPreset() ─▶ set 4 StateFlows ─▶ existing persist ─▶ RovaSettings
                                      │
edit stepper ─────────────────────────┘
                                      ▼
                         activePresetId = PresetMatcher.match(...)   (live "Custom" detection)

record start ─▶ SessionConfig snapshots the 4 values (UNCHANGED path) ─▶ service/scheduler (UNCHANGED)
```

No `SessionManifest` bump. No recovery / scheduler / merger change.

## 6. Accessibility (ADR-0020 standing requirement)

- Each preset chip is a `button` with `contentDescription` spelling out the bundle, e.g.
  *"Standard preset, 30 seconds every 2 minutes, 20 times, FHD."*
- Selected state exposed via `semantics { selected = true }`.
- Touch targets ≥48dp, ≥8dp spacing.
- No new animation (or gated through existing `ReducedMotion` / `checkA11yAnimationGated`).
- Presets never lock orientation (WCAG 1.3.4) — orientation untouched.

## 7. Behaviors

1. **Modified → Custom.** Apply a preset, then nudge any stepper → `activePresetId` becomes `null`
   → "Custom". Editing values back to a built-in tuple re-selects that built-in (intended).
2. **First-run default = Standard — WITHOUT clobber (codex #1, critical).**
   Today's raw getter defaults are effectively *Quick Sample* (`10/1/10/FHD`). Do **NOT** change
   getter defaults. Instead: on a **proven fresh install** (`onboardingCompleted == false` and no
   prior recording prefs written), seed Standard's four values **once**. Existing users' stored
   values are left untouched and simply classified by `PresetMatcher`.
3. **Existing users — never clobbered.** On launch, `activePresetId` classifies the current
   persisted values: exact (canonicalized) match → that built-in selected; else "Custom". No
   migration writes, no overwrites.

## 8. Testing (JVM-only, per project policy)

- `PresetMatcher`: exact match per built-in; no-match → null; resolution-alias canonicalization
  (`1080p`/`UHD`); `-1` sentinel matches Continuous; boundary tuples.
- `BuiltInPresets`: exactly 4; ids namespaced `builtin.*` and unique; **value tuples pairwise-
  distinct**; every value within `RecordSettingBounds` (with `-1` allowed only as the continuous
  sentinel).
- `RovaPreset` JSON: envelope round-trip (`presetSchemaVersion=2`); **legacy back-compat** —
  read `"[]"`, read legacy array-of-objects, derive missing id; **one malformed object skipped, rest
  survive**; reserved `builtin.` prefix on a loaded custom is re-namespaced.
- `applyPreset`: sets all four flows; does not alter orientation.
- First-run seeding: fresh install → Standard seeded once; second launch does not reseed.
- No-clobber: pre-existing non-matching values survive launch and classify as "Custom".

## 9. ADR-0026 + gate

- **ADR-0026** — "Preset = config bundle only; orientation is orthogonal and never carried by a
  preset. Built-in presets are code-defined and read-only." Following the project convention
  (invariant → `check*` → `preBuild`).
- **`checkPresetNoOrientation`** — static scan failing the build if `RovaPreset` (or
  `BuiltInPresets`) gains an orientation/mode field. Wired into `preBuild` like the existing 29 gates.

## 10. Files touched (anticipated)

| File | Change |
|---|---|
| `data/BuiltInPresets.kt` | **new** — 4 read-only built-ins |
| `data/RovaSettings.kt` | extend `RovaPreset` (`id`, `isBuiltIn`); envelope writer |
| `data/PresetMatcher.kt` | **new** pure helper |
| `ui/screens/RecordViewModel.kt` | `applyPreset`, `activePresetId`, `allPresets`; tolerant loader |
| `ui/screens/RecordScreen.kt` (+ chrome) | preset chip row + summary + "Customize" door |
| `app/build.gradle.kts` | register `checkPresetNoOrientation`, wire to `preBuild` |
| `docs/adr/0026-*.md` | **new** ADR |
| `app/src/test/...` | tests per §8 |

## 11. Open / deferred

- Exact preset *values* are unvalidated for this niche paradigm — beta-tune the constants.
- Flexible per-clip "phases" deferred to a later slice; schema envelope is additive-ready.
- Orientation cell may later be re-labelled "Layout/Orientation" for extra clarity — out of scope here.

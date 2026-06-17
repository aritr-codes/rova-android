# ADR-0031: In-app icon &amp; glyph system — outlined-default duotone, semantic tint seam, locked status

Status: **Accepted** (2026-06-16) — design approved; **P0 landed 2026-06-16** (`SemanticIcon` seam + status-color lock + `RovaIcons` collision map + gates `checkSemanticIconNoRawAlpha` §4 / `checkStatusColorLocked` §3); P1/P2 remain deferred
Refines: ADR-0028 §9 ("Icons — separate later PR"). Reuses ADR-0028 §3 (locked `RovaSemantics`), ADR-0020 (WCAG 2.2 AA), ADR-0022 (strings in resources).
Design spec: `docs/superpowers/specs/2026-06-16-icon-glyph-system-design.md`. Visual exploration: `.superpowers/brainstorm/…/board-3-semantic.html`.

## Context

The in-app icon set is two disjoint systems — 8 bespoke `ImageVector`s in `RecordChromeIcons.kt` vs `androidx.compose.material.icons` (a pinned set) everywhere else — with 63 distinct Material glyphs, 10 semantic conflicts, ~17 fragmented size values, and **20 raw `Color.White.copy(alpha=…)` tint sites** that bypass any seam. The Liquid Glass theme engine (ADR-0028) is the next program; it drives surface color from palette tokens. If it is built over the current icon layer it will bake in raw alphas and ambiguous glyphs. The icon language must therefore be fixed **first**, mirroring the established **identity-vs-locked** color split (`LibraryColors` / `RovaSemantics`).

This is a presentation-only program. No reliability/behavior change. Delivered foundation-first and tiered.

## Decision

1. **One visual language — outlined-default, soft-monoline duotone ("System D").** 24-grid, 1.9px stroke, round caps/joins, soft corners. A single **accent detail channel** (duotone) layered on a light outline; **meaning must survive without the accent (mono-safe)**. Filled = selected/active or a primary action only.

2. **One concept → one glyph.** A canonical concept→glyph map (spec §10/§E1) is authoritative; the same action never shows two glyphs. Resolves the 10 audited conflicts and the cross-role collisions (Record action vs nav vs recording-state; Library vs Play; Settings sliders→gear; Sort state-free; Warning-status vs Notifications-setting).

3. **Status colors are locked; everything else is the retintable accent channel.** recovered/interrupted/processing/success/warning/rec bind to `RovaSemantics` (ADR-0028 §3) and never retint or take per-call alpha. The duotone accent channel is what the theme engine retints per palette. Status is always paired with shape, never color-alone (WCAG 1.4.1).

4. **A single `SemanticIcon` tint seam.** All glyph color flows through one content-color contract — the single entry-point the theme engine drives. The 20 raw-alpha call-sites are replaced by it. This is the icon analogue of `GlassResolver`/`LibraryColorSpec`.

5. **Bespoke vectors have one home (`RovaGlyphs`); stock glyphs are aliased (`RovaIcons`).** `RecordChromeIcons.kt` folds into `RovaGlyphs`. Bespoke glyphs are authored to the locked grid/stroke so custom + stock read as one family. Bespoke is reserved for brand-unique/icon-poor concepts (DualShot, DualSight, Vault-collection, Recovery, Background-record, Merge); the rest stay stock behind the alias map.

6. **Over-media glyphs get contrast at the substrate.** Record/over-media glyphs sit on a Liquid-Glass chip meeting SC 1.4.11 ≥3:1 over worst-case frames — never solved by stroke weight. Active nav = filled glyph + accent + glass-chip container (FILL 0→1, animatable).

7. **One sizing scale.** `18 / 20 / 24 / 30 / 56` dp, one registry, folding the `RecordChromeTokens` / `LibraryDimens` duplicated-token overloads.

8. **Tiered migration (load-bearing order).** **P0 before the theme engine:** collision fixes + the `SemanticIcon` seam + status-color lock. **P1 with the engine:** bespoke `RovaGlyphs` + accent/glass-chip palette wiring. **P2 later:** animated states + secondary concepts + onboarding-illustration refresh. The theme engine MUST NOT begin until P0 lands.

## Enforcement (new gates, finalized in the P0/P1 plans — follow invariant → `check*` → preBuild)

- `checkSemanticIconNoRawAlpha` (P0, **BUILT 2026-06-16**, §4): forbids a raw `Color` literal on any `tint =` argument outside `ui/components/SemanticIcon.kt`; keyed on the `tint =` argument over a 3-line window (catches wrapped/conditional tints); `// semanticicon-opt-out: <reason>` hatch for genuinely non-themed glyphs (e.g. the flash-ON hardware-state indicator).
- `checkStatusColorLocked` (P0, **BUILT 2026-06-16**, §3): forbids any `.copy(...)` on a `RovaSemantics.<member>` (no per-call alpha/channel mutation of a locked status color). The status→`RovaSemantics` mapping itself is covered by `SemanticIconSpecTest` (JVM).
- `checkRovaGlyphHome` (P1, **BUILT 2026-06-17**, §5): forbids `ImageVector.Builder(` outside `ui/theme/RovaGlyphs.kt` (whole-file, comment-stripped scan so a builder split across lines is still caught). Landed with the `RecordChromeIcons.kt` fold-in (that file deleted; its 5 still-used vectors relocated into `RovaGlyphs`, the 3 superseded dropped) — subsumes the `RecordChromeIcons.kt` allowance in `checkRecordSurfaceNoBlur`.
- Reused: `checkA11yTargetSizeToken` (≥24dp), `checkA11yAnimationGated` (reduce-motion), `checkNoHardcodedUiStrings` (en+es), `ThemeContrastTest`/`ContrastMath` for glyph+chip ratios.

Pure-Kotlin + JVM-tested: the `SemanticIcon` resolver, the concept→glyph map, and the size registry follow the project seam/pure-helper pattern.

## Consequences

- No manifest/schema change; no behavior change. Presentation-only, per-surface migration (the `RovaIcons` alias map makes most swaps one-line).
- The theme engine inherits a stable icon color seam (the explicit reason to sequence icons first).
- ADR-0028 §9's deferred "Icons PR" is now specified and split P0/P1/P2; the launcher/splash asset (ADR-0028 §9, §12) stays out of scope.
- DualSight's glyph is designed now even though the *feature* remains hardware-blocked (ADR-0029 / PR #115) — so the icon system is complete and the feature can light up its glyph when unblocked.
- New `check*` gates raise the gate count when P0/P1 land (currently 42 on master); each lands with its ADR clause + preBuild wiring, never edited to pass.

## Alternatives considered

- **Stay on Material outlined (System A)** — rejected: thin lines vanish over media, no brand voice, leaves the two-system split unresolved.
- **All-bespoke rounded (System B)** — rejected: all-custom cost, reads soft/juvenile, weight fights dense chrome.
- **Hybrid on stock glyphs (System C)** — rejected: brand only in the chip; keeps generic glyphs for Rova-unique concepts.
- **Defer icons until inside the theme-engine program** — rejected: forces icon decisions under engine pressure and bakes raw alphas into the palette wiring (the precise failure this ADR prevents).

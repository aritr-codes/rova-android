# Rova — UI Phase 2 Audit: Icon System, Surfaces & Theme Engine

> Verify-don't-assume audit against `board-3-semantic.html` (Phase 3 Semantic Glyph Design, the source of truth) and the live `master` (`d4ef5b5`). Produced 2026-06-19 from a 4-agent parallel audit (icon-code · surfaces · theme-engine · synthesis). **The icon seam, the locked-status contract, and the theme engine are all LIVE and well-tested — the remaining work is adoption, not architecture.**

Source of truth: `.superpowers/brainstorm/1234-1781611237/content/board-3-semantic.html` — **60 glyphs** in library `G`, **30 semantic roles** in the E1 map, E2 migration ladder (P0 done · P1/P2 remaining).

---

## 1. Icon / Glyph audit — what's unfinished

**Shipped (13):** 10 board glyphs authored as two-layer `RovaGlyph` ImageVectors in `ui/theme/RovaGlyphs.kt` — `lib_stack`(Library), `set_gear`(Settings), `sort_bars`(Sort), `rec_disc`(Record), `ds_twin`(DualShot), `vault_stack`(Vault), `recov_rejoin`(Recovery), `dualsight`(DualSight), `bg_record`(BackgroundRecord), `merge_stitch`(Merge); plus animated `ProcessingGlyph` (proc_arc/merging), a `Play` vector, and `rec_morph` realized as a styled red `Box` (RecordChrome.kt:821-826, not a glyph).

**Missing (29 board glyphs):** the everyday-action set the app still draws from stock Material — `search, share, delete, favorite/favorite_on, select, pause, view, edit, theme, volume` — and the status/secondary marks — `warn_tri`(locked amber), `interrupted`(escalating orange), `notif_bell/notif_off`, `rec_clipcheck`(green Recovered), `proc_dots`(static processing fallback — today wrongly reuses `merge_stitch`), `loop_interval`, `seg_count`, `waiting`, plus `rec_ring`(Record-nav) and intentionally-unbuilt design alternates (`set_sliders/grid/tune`, `sort_*` variants, `ds_*` variants, `vault_*` variants, `lib_*` variants, `recov_*` variants).

**Legacy call-sites (19 clusters still on raw `Icons.*`):**

| Surface | Status | Notes |
|---|---|---|
| Record home (FAB + chrome) | **MOSTLY NEW** | FAB renders rec_disc but **white, not accent**; Stop = styled Box |
| Bottom nav / record-bar | **NEW** | `ModeCycleChip` is the only control that fully realizes the glass-chip+accent pattern |
| Recovery, capture/orientation picker, merge-complete card | **NEW** | — |
| Library top bar | **PARTIAL** | Sort migrated; lock/search/view-toggle still raw (`LibraryTopBar.kt:76/85/103`, `LibrarySearchField.kt:46/50`) |
| Library cards/rows/sheets/batch bar | **LEGACY** | densest cluster — `LibraryBatchBar`, `LibraryItemSheet`, `LibraryHeroCard`, `LibraryGridCard`, `LibraryListRow`, `LibrarySelectionTopBar`, `LibraryRow`, `RovaCardComponents` |
| Settings | **LEGACY** | ~30 rows on `Icons.Default.*` (`SettingsScreen.kt`) |
| Warnings | **LEGACY** | `WarningSheetContent.kt` per-warning stock icons (seam partial) |
| Onboarding | **LEGACY** | `OnboardingSlide.kt` |
| Vault | **LEGACY** | `VaultScreen.kt` Lock/Warning |
| Player | **PARTIAL** | wrapped in SemanticIcon but fed stock PlayArrow/Pause/Edit |
| Notification (system) | **OUT OF SCOPE** | needs a drawable resource, not an ImageVector |

---

## 2. Icon ship plan (7 PRs, behavior-preserving)

- **PR-I1 (L)** — Author the everyday-action glyphs in `RovaGlyphs.kt` + flip `RovaIcons.kt` bridges off stock Material (`search, share, delete, favorite/_on, select, pause, view, edit, theme, warn_tri, notif_bell/off`). Pure additive; no call-sites change. `checkRovaGlyphHome` stays green.
- **PR-I2 (M)** — Migrate the **Library batch/sheet/card/row** cluster to `SemanticIcon + RovaIcons.*` (delete via `IconStatus` danger-red; favorite outline→filled).
- **PR-I3 (S)** — Migrate the **Library top bar + search field + empty/error states**; completes the Library surface to E1.
- **PR-I4 (S)** — **Player** controls: swap the fed vector to `RovaIcons.Play/Pause/Edit` (already SemanticIcon-wrapped).
- **PR-I5 (M)** — **Warnings + Vault**: route per-warning icons through `IconStatus.Warning` (locked amber), `warn_tri`/`notif_off`/`vault_stack`.
- **PR-I6 (M)** — **Settings + Onboarding**: `theme`, `loop_interval`, `notif_bell`, remaining rows through the seam.
- **PR-I7 (M)** — Author the marks no call-site can fake: `rec_clipcheck`, `interrupted`, canonical `rec_morph`, `waiting`, `proc_dots` (correct static fallback), `loop_interval`/`seg_count`. Feeds the FAB lifecycle (§3). `rec_ring` deferred unless a Record nav item is added.

---

## 3. FAB / Settings / Record-bar — theme-aware circular backgrounds

**Verified file homes:** FAB + record-bar + bottom-nav all live in `ui/screens/RecordChrome.kt` (NOT `MainScreen.kt`, which is a bare NavHost). Settings on Record-home opens via `FloatingSettingsPanel`.

**Current state:** FAB is a 56dp circular container but **theme-blind** — `rec_disc` renders white (`IconRole.Default→textHigh`), not the board's "solid **accent** disc"; container fill/stroke hardcoded. Flash/Flip are palette-aware `GlassCircleButton`s (48dp hit / 30dp glass). `ModeCycleChip` is the **only** control that fully realizes "glass-chip active container + duotone accent wired to palette." FAB states collapse: `Waiting`→looks like Stop, `Merging`→looks like Disabled. All targets pass SC 2.5.8 (≥24dp).

**Recommendation — build one shared `SemanticIconButton` (new `ui/components/SemanticIconButton.kt`)** that *composes* `SemanticIcon` + `GlassSurface` (so `checkSemanticIconNoRawAlpha` stays intact — no new raw tints):

```
SemanticIconButton(glyph, contentDescription, onClick,
    role: IconRole = Default, status: IconStatus? = null,
    active: Boolean = false, shape = CircleShape, enabled = true, modifier)
```

- **Palette read** (ADR-0028 / `LibraryColorSpec` identity-vs-locked): container reads `LocalGlassEnvironment.current.palette`. Idle = `GlassResolver` output (scene-relative, preserves today's look). Active = palette-derived accent-glass (fill = `palette.accent` with the ModeCycleChip accent→accent2 gradient, stroke = `palette.edge`). Inner tint flows through SemanticIcon: locked status wins, else role; FAB capture mark uses **`IconRole.Accent`** so `rec_disc` finally reads `palette.accent`.
- **Touch targets:** `sizeIn(min 24.dp)` floor; each call-site keeps its existing hit box (FAB 56dp, cam controls 48dp, nav 42dp). `ModeCycleChip` gains a stable min-square to match the 44dp config-cell rhythm.
- **AA contrast:** every new active accent-on-glass combo added to `TokenContrastTest` (all 12 palettes via `ContrastMath`); reuse `DialogActionColors.resolve` for on-accent labels.
- **FAB lifecycle:** extend `RecordFabState` / `recordFabState()` from `{Start,Stop,Disabled}` to the board FB set `{rec_disc, rec_morph, rec_ring, waiting, proc_arc}`; re-map `Waiting`→waiting glyph and `Merging`→reuse the shipped `ProcessingGlyph`. Keep the `SpinningBox`/semantics/live-region wrapper (PR-ε contract).
- **Usability: net improvement** — FAB picks up brand accent, and Waiting/Processing stop being indistinguishable from Stop/Disabled. **Risks:** (1) a palette-reactive FAB on the deliberately pinned-dark record route could regress AA-over-camera — gate every combo through `TokenContrastTest`; (2) the white-on-accent gradient is an owner-approved WCAG exception **only for ModeCycleChip** — extending it needs sign-off; (3) author `proc_dots` so the FAB's reduced-motion fallback isn't `merge_stitch`.

---

## 4. Theme-engine critique

**Strengths:** single `ColorScheme` factory (`PaletteColorScheme.from`, gated by `checkSingleColorSchemeSource`); framework-free JVM-testable core (`PaletteColorScheme`/`GlassResolver`/`LibraryColorSpec`/`SemanticIconSpec`/`ContrastMath`); locked-vs-identity discipline (`RovaSemantics` immutable across 12 palettes); AA-validated locked errors (`onError=NearBlack` because white-on-#EF4444 is 3.95:1); pinned routes handled at both glass + scheme layers; single icon-tint seam; direction-correct surface ladder.

**Limitations & gaps (code-grounded):**
- `darkTheme`/`dynamicColor` params on `RovaTheme` are **vestigial** (scheme comes from the palette).
- Light/dark is **not orthogonal** — `isLight` is one boolean; only 1 of 12 palettes (Daylight) is light; `FOLLOW_SYSTEM` discards the chosen accent in light mode.
- **Record chrome is theme-blind except accents** — `RecordChromeTokens` hardcodes ~80 raw `Color.Black/White.copy(alpha)` (incl. `dotRecording` re-hardcoding the locked error red — a 2nd source of truth).
- `reduceTransparency` is wired to the **wrong OS signal** (`ReducedMotion.isReduced`) — users who set reduce-transparency but not reduce-motion never get the solid glass path.
- `PaletteColorScheme.from` is **unmemoized** — recomputed per recomposition, twice on pinned subtrees.
- 3 manual `ThemeSelection` mirrors (`rovaPalettes` map, `ThemeContrastTest.sceneBottom`, picker exposure) fail at runtime/test, not compile time; Wave-2 palettes shipped-but-hidden.
- `tertiary` aliases `secondary` (no third hue); two parallel type systems (M3 Typography vs ~30 `RovaTokens` TextStyles).
- **Biggest a11y hole:** contrast over **live camera** is explicitly unguaranteed — the test's worst case is the gradient's darkest stop, but the blur path samples real (possibly bright) footage.

**Ranked improvements:** see roadmap §5 (deduplicated with the icon/surface work).

---

## 5. Prioritized roadmap

| Pri | Task | Impact / Effort | Why |
|---|---|---|---|
| **P0** | Replace the `reduceTransparency` proxy with a real OS reduced-transparency/high-contrast read | High / **S** | Pure signal swap; an a11y feature that silently never triggers today. No visual regression. |
| **P0** | Build `SemanticIconButton` glass-chip + wire the FAB to `palette.accent` + full lifecycle (rec_disc/rec_morph/rec_ring/waiting/proc_arc) | High / **M** | Fixes white-FAB-on-every-theme + indistinguishable Waiting/Merging; unblocks FAB+nav+topology consistency. ProcessingGlyph already exists. *(gated on §6 decisions)* |
| **P1** | PR-I1→I3: author everyday-action glyphs + migrate the **entire Library** surface | High / **L** | Biggest single jump in icon-system adoption; closes the densest LEGACY cluster. Behavior-preserving. |
| **P1** | Memoize `PaletteColorScheme.from` with `remember(palette)` | Med / **S** | Removes per-frame scheme rebuild on record/player subtrees. No behavior change. |
| **P1** | PR-I4→I7: Warnings/Vault/Settings/Onboarding migration + author status marks (rec_clipcheck/interrupted/warn_tri/notif_bell) | Med-High / **M** | Completes E1 coverage; status marks get bespoke locked-color glyphs. |
| **P2** | Adaptive scrim for chrome text over live camera (reuse the api31 RenderEffect blur) | High / **M** | The single biggest a11y hole; integration not greenfield. Prereq for P2 RecordChromeTokens work. |
| **P2** | Make `RecordChromeTokens` palette-derived (remove ~80 hardcoded alphas + dotRecording duplication) | Med / **L** | Record chrome becomes theme-aware. Do **after** adaptive scrim so AA-over-camera holds. |
| **P2** | Material You as a 13th "Wallpaper" `ThemeSelection` + theme-crossfade on swap | Med / **M** | The dead `dynamicColor` param; needs a `from()` bypass + 3rd allowlisted file in `checkSingleColorSchemeSource`. *(gate-widening decision)* |
| **P2** | Bind enum→registry→contrast-test→picker couplings to fail at compile/test time | Med / **M** | Hardening; derive `sceneBottom` from the palette gradient; expose Wave-2. |
| **P3?** | True light/dark pairing per accent (restructure `RovaPalette` so FOLLOW_SYSTEM keeps the accent in light) | High / **L** | High-impact but large; not scheduled — owner call. |

---

## 6. Open decisions (gate implementation)

1. **FAB on the pinned-dark record route** — retint to brand accent per board (`rec_disc = solid accent disc`), or keep neutral-white to preserve the deliberate pinned-dark aesthetic, or use a darker accent-glass variant that hits full 4.5:1? *(load-bearing for the surface plan)*
2. **White-on-accent WCAG exception** — currently approved only for `ModeCycleChip`. Extend it to the FAB + active glass-chips, or require the AA-safe darker variant?
3. **Material You** — widen `checkSingleColorSchemeSource`'s allowlist (currently exactly `{Theme.kt, PaletteColorScheme.kt}`) for a 3rd bridge file, or keep MY out to preserve the strict single-source invariant?
4. **rec_ring / Record-nav** — add a dedicated Record nav item (author `rec_ring`), or keep the FAB-as-record-action model and skip it?
5. **Stop affordance** — swap the styled red Box (RecordChrome.kt:821-826) for the canonical `rec_morph` glyph, or leave the Box (visually board-faithful, no morph cost)?
6. **Settings trigger drift** — Record-home opens settings via a ChevronUp pill, gear only in the nav tab. Accept (chevron=disclosure, gear=navigation) or align both to `set_gear`?
7. **RecordChromeTokens palette-derivation** — only after adaptive scrim, or keep record chrome neutral-by-design and just fix the `dotRecording` duplication?
8. **Light/dark pairing per accent** — schedule the `RovaPalette` restructure, or accept the single Daylight light palette for v1?

---

## 7. Reconciled execution order (codex-reviewed 2026-06-19)

**Owner decisions:** FAB = AA-safe darker accent variant (no WCAG exception); modern theming (Material You + light/dark pairing) → **backlog** (do NOT widen `checkSingleColorSchemeSource`).

**Codex corrections (source-verified):**
- The pinned-dark route **already carries the user accent** — `PinnedGlassEnvironment.forPinnedRoute` copies `accent/accent2/accentOnDark/accentContainerOnDark` onto `NeutralDarkRecordPalette`. No new accent-propagation mechanism needed.
- **FAB color = `MaterialTheme.colorScheme.primary`/`onPrimary`** (already AA-derived via `DialogActionColors.resolve`; `RovaDarkSurface` rebuilds the scheme from the accent-carrying pinned palette → `primary` = user accent, solid). No new token, **no 2nd ColorScheme source**. Do NOT reuse `accentContainerOnDark` (translucent `0.22α`).
- **FAB is NOT a generic glass icon button** — build a pure `RecordFabVisualSpec` mapper (`RecordFabUiState → container/content/ring/glyph/progress/enabled/a11yLabel`, JVM-tested) rendered on the existing stable circular surface; add `IconRole.OnAccent` so the glyph still flows through `SemanticIcon`. `SemanticIconButton` is built for **nav/topology active chips**, not the FAB.
- The FAB PR must change **`RecordScreen` click-handling** (action contract for the new Waiting/Merging/Processing states), not just `RecordChrome`.
- Author glyphs in **batches**; split Player out from Settings/Onboarding/Vault/Warnings.

**Final PR sequence** (stacked off master; subagent-driven-dev — subagents edit-only, controller runs all gradle/commits/smoke; each PR lands JVM tests + 46 gates green; capture surfaces get mandatory device smoke):

1. **PR-1 Theme hygiene** — ✅ **MERGED 2026-06-19, PR #122 → master `5d8c292`.** `reduceTransparency` ← real high-contrast-text signal (`Settings.Secure "high_text_contrast_enabled"`) OR reduce-motion; memoized `PaletteColorScheme.from` via `remember(palette)` in `Theme.kt`. New pure `ReducedTransparency` seam + JVM test.
2. **PR-2 FAB lifecycle** — ✅ **MERGED 2026-06-19, PR #123 → master `8225c80`** (owner device-smoke GO). Reworked to match `board-3-semantic.html`'s `FB` row **exactly** (the first cut, which reused primitives, was a NO-GO). Pure `RecordFabVisualSpec` (visual ⟂ action) → 5 board states {Idle accent-gradient disc + `rec_disc` · Recording red-gradient disc + `rec_morph`, no ring · Waiting ghost + `waiting` hourglass · Processing ghost + spinning `proc_arc` · Disabled ghost + `rec_ring`}. Authored `RecordRing`/`Waiting`/`ProcArc`/`ProcDots` glyphs; `rec_morph` = white Box. **AA:** disc fills the `DialogActionColors`-deepened accent gradient + new `IconRole.OnAccent` (white/black — full 4.5:1, no exception). `RecordScreen` action contract via `FabAction`. `ThemeContrastTest` pins accent-ghost ≥3:1 + OnAccent ≥4.5:1 × 12 palettes. (Note: plan said "FAB reads `colorScheme.primary`" — actual uses the deepened-gradient resolver directly so light accents stay AA on the always-white pinned route.)
3. **PR-3 Icon foundation** (batched) — **NEXT.** Author the remaining everyday-action glyphs (search/share/delete/favorite/_on/select/pause/view/edit/theme) + status (warn_tri/notif_bell/_off) in `RovaGlyphs`; flip `RovaIcons` bridges. Additive. *(Note: `RecordRing`/`Waiting`/`ProcArc`/`ProcDots` already shipped in PR-2 — this batch shrinks accordingly.)*
4. **PR-4 Library migration**: whole Library surface → seam; delete→`IconStatus` danger; favorite outline→filled.
5. **PR-5 Remaining surfaces**: Player (split) ; then Warnings/Vault/Settings/Onboarding + author `rec_clipcheck`/`interrupted`.
6. **Backlog** (filed, not scheduled): P2 adaptive scrim over live camera → RecordChromeTokens palette-derivation; Material You 13th theme; true light/dark pairing per accent. **One open question carried to PR-3+:** the Disabled FAB renders board-literal faint-ring + **accent core** (`rec_ring`); owner GO'd it 2026-06-19 but revisit if a fully-dim disabled reads better.

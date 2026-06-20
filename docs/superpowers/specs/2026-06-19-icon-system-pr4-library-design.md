# UI Phase 2 PR-4 — Library surface icon migration (design)

**Status:** Approved (owner, 2026-06-19). Branch `feat/library-icon-pr4` off master `881f8c8` (PR-3 / #124 merged).
**Predecessor:** PR-3 (#124) authored the everyday-action + status glyphs in `RovaGlyphs` and added `IconStatus.Danger → RovaSemantics.error`. PR-4 *consumes* them on the Library surface.
**Plan of record:** `docs/UI_PHASE2_ICON_THEME_AUDIT.md` §7 (5-PR sequence). This is PR-4.

## Goal

Route the Library surface's **identity-action** icons through the `SemanticIcon` seam using the bespoke PR-3 glyphs, formalize the destructive Delete through `IconStatus.Danger`, and make Favorite read as outline→filled-**accent** — without authoring new glyphs and without touching nav/utility chrome.

## Non-goals (explicit scope fence)

- **No new glyphs authored.** PR-4 only consumes glyphs PR-3 already shipped. Concepts with no bespoke glyph (Back, Close, Clear, Select-All, hero/sheet Play, empty-state, filter-chip Check) stay stock Material `Icon(...)` — deferred to a later PR.
- **No nav/utility migration** (owner decision "identity glyphs only", 2026-06-19).
- **Not** the full-surface "every icon through the seam" sweep (the larger alternative; declined).
- No Player / Warnings / Vault-screen / Settings / Onboarding / Record-home work (those are PR-5).
- No new gate, no new user-facing strings (all labels already exist).

## Owner decisions (2026-06-19)

1. **Depth = identity glyphs only.** Flip the ~9 concepts that have a bespoke PR-3 glyph; leave pure nav/utility stock.
2. **Favorite tint = accent on surfaces, media-safe on the hero.** Filled-accent star in controlled surfaces (item sheet, batch bar, grid/list selection contexts); the hero-card top-right star keeps the media-legible `libraryColors.overlayText` tint (it sits over arbitrary poster media with no scrim and reads as a *subordinate ghost action*).

## Migration map (concept → call-site → target)

The Library surface = `app/src/main/java/com/aritr/rova/ui/library/components/*` + `LibraryTopBar` / `LibrarySearchField`. Already-on-seam sites (Sort `LibraryTopBar:93`, item-sheet Vault `LibraryItemSheet:231`, sort-sheet Check `LibrarySortSheet:76`) are **not touched**.

| Concept | Call-site(s) | Target |
|---|---|---|
| Search | `LibrarySearchField:46` (leading), `LibraryTopBar:84` (nav) | `RovaIcons.Search`, role `Secondary` |
| Share | `LibraryBatchBar:63`, `LibraryItemSheet:124` | `RovaIcons.Share`, role `Default` |
| Vault | `LibraryBatchBar:65`, `LibraryTopBar:75` (nav) | `RovaIcons.Vault` *(existing glyph)*, `Default` / `Disabled` |
| Favorite **state**, over media | `LibraryHeroCard:135/137` | bespoke star (`RovaGlyphs.FavoriteOn`/`Favorite` outline), **media-safe `overlayText`** tint |
| Favorite **state**, solid surface | `LibraryItemSheet:129/131` | `RovaIcons.FavoriteOn`/`Favorite`, role `Accent` |
| Favorite **action** | `LibraryBatchBar:71` | `RovaIcons.FavoriteOn`, role `Default` *(uniform strip)* |
| Select **toggle** | `LibraryGridCard:167`, `LibraryListRow:96` | selected → `RovaIcons.Select` role `Accent`; unselected → stock `RadioButtonUnchecked` |
| Select **action** | `LibraryItemSheet:127` | `RovaIcons.Select`, role `Default` |
| Delete **destructive** | `LibraryItemSheet:142` (`danger=true`) | `RovaIcons.Delete`, **`status=IconStatus.Danger`** |
| Delete **action** | `LibraryBatchBar:72` | `RovaIcons.Delete`, role `Default` *(neutral strip)* |
| View layout | `LibraryTopBar:103` (grid affordance only) | `RovaIcons.View`, role `Secondary`; list affordance stays stock |
| Edit | `LibraryItemSheet:132` | `RovaIcons.Edit`, role `Default` |

**Untouched** (identity-only fence): `LibrarySearchField:50` (Clear), `LibrarySelectionTopBar:48/64` (Close/Select-All), `LibraryTopBar:61` (Back), `LibraryStates:88` (empty-state), `LibraryHeroCard:153` + `LibraryItemSheet:123` (Play), `LibraryFilterChips:116` (chip-internal Check), `LibraryItemSheet:133` (Details = stock `Icons.Outlined.Info`).

## Three scoped judgment calls (owner-ratified in design review)

1. **Delete is danger-red only where it is destructive.** `IconStatus.Danger` at the item-sheet Delete (already `danger=true` / error-tinted — the seam formalizes it). Batch-bar Delete stays neutral `Default` so the action strip (Share/Vault/Favorite/Delete) keeps one uniform tint; the actual destruction is gated behind a confirm dialog downstream.
2. **Batch-bar Favorite stays neutral** (`Default`, not accent) — there it is an *action verb* ("favorite these"), not a state indicator. Accent is reserved for favorite **state** (the sheet toggle).
3. **View toggle migrates only the grid affordance** to `RovaIcons.View` (PR-3 authored grid, not list). The list affordance stays stock; the two never render simultaneously (it is a toggle), so there is no side-by-side bespoke/stock mismatch.

## Components changed (4)

1. **`BatchAction`** (`LibraryBatchBar.kt`): parameter `icon: ImageVector` → `glyph: RovaGlyph` + `role: IconRole`; the icon renders via `SemanticIcon(glyph, role = …)`; the label `Text` keeps its own computed color. All four batch actions (Share/Vault/Favorite/Delete) now have glyphs. Disabled actions (Vault when `!vaultEnabled`) pass `role = IconRole.Disabled`; the seam applies the centralized disabled alpha — the local `onSurfaceVariant.copy(alpha = 0.5f)` icon tint is removed (the label keeps a disabled color).
2. **`SheetRow` (RovaGlyph overload)** (`LibraryItemSheet.kt`): extend with optional `status: IconStatus? = null` and a role override so Delete can pass `status = IconStatus.Danger` and Favorite can pass `role = IconRole.Accent`. Share/Edit/Select/Favorite/Delete move from the `ImageVector` overload to this overload. A non-null `status` suppresses the role tint (seam contract).
3. **`LibraryGridCard` / `LibraryListRow`**: the *selected* indicator → `SemanticIcon(RovaIcons.Select, role = IconRole.Accent)`; the *unselected* `RadioButtonUnchecked` stays stock (no empty-circle glyph exists, and it is not an identity mark).
4. **`LibraryHeroCard`**: the top-right favorite renders the bespoke star outline (`RovaGlyphs.FavoriteOn.outline` when set / `RovaGlyphs.Favorite.outline` when unset) tinted `libraryColors.overlayText`. This is a **deliberate seam-exception**: the seam's `IconRole`s map to palette tokens, but a star over arbitrary poster media needs the media-legible `overlayText` token, not a palette role. Rendered as a plain `Icon(vector, tint = libraryColors.overlayText)` — `overlayText` is a derived token, not a `Color.` literal, so it does **not** trip `checkSemanticIconNoRawAlpha`.

## Pure helper + tests (house convention)

The migration is mostly Compose call-site flips, which are not JVM-unit-testable under `isReturnDefaultValues = true`. The testable seam:

- **`LibraryIconSpec.kt`** (pure, no Compose/Android): the state→glyph/status choices, e.g. `favoriteGlyph(isFavorite: Boolean): RovaGlyph` (→ `RovaGlyphs.FavoriteOn` / `RovaGlyphs.Favorite`) and `deleteStatus(destructive: Boolean): IconStatus?` (→ `IconStatus.Danger` / `null`). Call-sites consume these so the decisions live in one tested place.
- **`LibraryIconSpecTest.kt`**: reference-identity assertions (same pattern as `PickerGlyphsTest`) — `favoriteGlyph(true) === RovaGlyphs.FavoriteOn`, `favoriteGlyph(false) === RovaGlyphs.Favorite`, `deleteStatus(true) == IconStatus.Danger`, `deleteStatus(false) == null`.

## Accessibility / gates / strings

- **Contrast — favorite/select accent (palette-derived):** already covered by the existing `ThemeContrastTest."selected-control accent meets 3 to 1 over every palette surface"` — accent is the same channel and ≥3:1 over the glass card/sheet substrate on all 12 palettes. **No new assertion needed.**
- **Contrast — delete danger (locked color):** `IconStatus.Danger → RovaSemantics.error` (#EF4444) is a **locked, non-theme-adaptive** status color (ADR-0031 §3). A blanket "error ≥3:1 over all 12 themed surfaces" assertion would **false-fail on the light Daylight sheet** — the exact trap the `escalating` merge-color test avoided by scoping to its pinned-dark substrate (`memory/project_icon_glyph_system.md`). The delete mark conveys meaning by **shape pairing** (the trash glyph, WCAG 1.4.1), not color alone, and the row's text label keeps `MaterialTheme.colorScheme.error` (theme-managed, unchanged from today). So **no all-palette danger contrast assertion is added.**
- **Only new test:** the pure `LibraryIconSpecTest` (state→glyph/status choices). Compose call-site migrations are verified by `assembleDebug` (46 gates + compile) + device smoke, since they are not JVM-unit-testable under `isReturnDefaultValues = true`.
- **Semantics:** call-site `contentDescription` / role semantics are unchanged (the labels and a11y descriptions already exist on these controls). Migrating the icon vector does not alter the accessibility tree.
- **Gates:** no new invariant → **no new `check*` task**; count stays **46**. `checkSemanticIconNoRawAlpha` stays green (hero exception uses a token tint). `checkStatusColorLocked` stays green (no `RovaSemantics.*.copy(`). `checkSingleColorSchemeSource` / `checkRovaGlyphHome` unaffected.
- **Strings:** none added (ADR-0022 N/A this PR).

## Testing & rollout

- `:app:testDebugUnitTest` (incl. new `LibraryIconSpecTest`) and `:app:assembleDebug` (all 46 gates + main compile) green before smoke. Confirm `:app:packageDebug` EXECUTED + fresh APK mtime before `adb install -r`.
- **Mandatory device smoke** on RZCYA1VBQ2H (Android 14) — Library is a visible surface. Library is *not* `FLAG_SECURE`, so `adb screencap` can self-verify the glyphs, but the owner gives the visual GO. Verify: search/share/edit/select/view glyphs render as the bespoke System-D shapes; favorite shows filled-accent in the sheet and media-safe white on the hero; the item-sheet Delete is danger-red while the batch-bar Delete is neutral.
- **codex review** of the seam wiring (`BatchAction`/`SheetRow` signature changes + the hero seam-exception + `LibraryIconSpec`) before the final commit, per the icon-system brief.
- Single commit at owner GO (spec + plan + code + tests + HANDOFF/BACKLOG refresh bundled, PR-3 style); push + PR + merge only on owner GO.

## Risks

- **Low.** Follows the P0 + Slice-3 seam-migration precedent exactly. The only novel bits are the hero media-safe seam-exception (documented, gate-safe) and the `BatchAction`/`SheetRow` signature changes (mechanical, covered by compile + the pure spec test + device smoke).

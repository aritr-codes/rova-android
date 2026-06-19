# Icon System — UI Phase 2 PR-3: Icon Foundation (design)

**Date:** 2026-06-19 · **Status:** Approved (owner + codex `019edfbc`) · **Branch (planned):** `feat/icon-foundation-pr3` off `master` (`7d3a996`, code state `8225c80`)
**Plan doc home:** `docs/UI_PHASE2_ICON_THEME_AUDIT.md` §7 (item 3) · **ADR:** 0031 (icon & glyph system) · **Source of truth:** `.superpowers/brainstorm/1234-1781611237/content/board-3-semantic.html`

## 1. Goal & non-goals

Author the remaining everyday-action and status glyphs as bespoke System-D `RovaGlyph`s (board-exact, no eyeballing) and repoint their `RovaIcons` concept→glyph map entries off stock Material. **Additive foundation only** — no app surface is rewired (that is PR-4 Library / PR-5 remaining surfaces).

**Non-goals (deferred):**
- Wiring any glyph into a screen / call-site → PR-4, PR-5.
- New user-facing strings / `contentDescription`s → they live at call-sites → PR-4/PR-5. **PR-3 adds zero strings**, so ADR-0022 (`checkNoHardcodedUiStrings`) does not apply.
- Encoding the locked status for the duotone status glyphs `Interrupted`/`RecClipCheck` into the map → PR-5 (where they wire); PR-3 only *authors* them.

## 2. Why this is safe / additive

Grep verified the three stock-Material bridge entries being flipped — `RovaIcons.View`, `RovaIcons.WarningStatus`, `RovaIcons.NotificationsSetting` — have **zero live call-sites** (only `RovaIcons.Details.glyph` is consumed, in `LibraryItemSheet`, and Details is out of scope). The new entries (`Search/Share/Delete/Favorite/FavoriteOn/Select/Pause/Edit/Theme/NotificationsOff`) are new concepts with no consumers. Repointing/adding map entries therefore breaks no compilation and changes no rendered pixel. **No device smoke required** (nothing visible changes); the build gate is the 46 static checks + JVM tests + the ultracode adversarial path-match pass.

## 3. The seam (recap — unchanged contract)

- `data class RovaGlyph(outline: ImageVector, accent: ImageVector? = null)` (`ui/theme/RovaGlyph.kt`).
- `SemanticIcon(glyph, role, status)` renders `outline` tinted by `status` (locked) else `role` (identity), then `accent` tinted by `palette.accent`. **A non-null `status` suppresses the accent layer** (whole mark = locked color).
- `IconRole { Default, Secondary, Disabled, Accent, OnAccent }`; `IconStatus { Recovered, Interrupted, Processing, Success, Warning, Rec }` → see §4 for the added `Danger`.
- Authoring helpers in `RovaGlyphs.kt` (the **only** allowlisted home, `checkRovaGlyphHome`): `glyph{}`, `strokePath{}`, `fillPath{}`, `svgStroke(d)`, `svgFill(d)`, `circle(cx,cy,r)`, `roundRect(x,y,w,h,rx)`, `seg(x1,y1,x2,y2)`. `PLACEHOLDER` brush + `SW=1.9f` declared at top (init-order bug guard, 2026-06-17).

**Board CSS channel → RovaGlyph layer:** default stroke / `class="fill"` = neutral → **outline**; `class="ac2"` / `class="ac2 solid"` = accent → **accent**. `solid` = filled (`fillPath`/`svgFill`), else stroked (`strokePath`/`svgStroke`).

## 4. Seam change: `IconStatus.Danger → RovaSemantics.error`

Destructive-delete needs a locked red. `RovaSemantics.error = Color(0xFFEF4444)` **already exists** (identical to `SignalRed` and what `colorScheme.error` resolves to; it is the app-wide destructive convention used by `RovaDialogs(destructive=true)`, `RecoveryCard` hard-red, `LibraryItemSheet(danger=true)`) but is **not yet mapped to any `IconStatus`**. `Rec` is a *different* red (`0xFFFF4D4D` = recording).

**Change:** add one enum case `Danger` and one `statusTint` branch `IconStatus.Danger -> RovaSemantics.error`. Reuses the existing locked red (no new color), keeps recording≠destructive distinct.

- **No gate edit:** `checkStatusColorLocked` only forbids `RovaSemantics.X.copy(...)` dilution — it pins no allow-list. Adding a case is gate-safe.
- **Test (covers codex's "colors slip in silently" risk):** `SemanticIconSpecTest` asserts `statusTint(IconStatus.Danger) == RovaSemantics.error` and that it is distinct from `Rec`.
- **Delete glyph stays neutral** (board `delete` has no `.ac2`). `Danger` is applied by PR-4 destructive call-sites (Library delete button / batch bar), not baked into the concept — delete is contextually-destructive, not intrinsically a status.

## 5. Glyphs to author (15) — board `d=`/element → DSL transcription

Verbatim from board `const G={…}`. `mono` = `accent=null`. `via role` = single-layer, rendered `IconRole.Accent` at call-site.

| # | val | board key | model | outline layer | accent layer |
|---|-----|-----------|-------|---------------|--------------|
| 1 | `Search` | `search` | mono | `circle(10.8,10.8,5.9)` + `svgStroke("M15.4 15.4 20 20")` | — |
| 2 | `Share` | `share` | duo | `svgStroke("M8.4 10.9 15 7.1")` + `svgStroke("M8.4 13.1 15 16.9")` | `fillPath{circle(6.2,12,2.3)}` + `circle(17.3,6,2.3)` + `circle(17.3,18,2.3)` |
| 3 | `Delete` | `delete` | mono | `svgStroke("M5 7h14")` + `svgStroke("M9 7V5.6A1.6 1.6 0 0 1 10.6 4h2.8A1.6 1.6 0 0 1 15 5.6V7")` + `svgStroke("M7.2 7 8.1 19.4A1.6 1.6 0 0 0 9.7 21h4.6a1.6 1.6 0 0 0 1.6-1.6L16.8 7")` | — |
| 4 | `Favorite` | `favorite` | mono, via role | `svgStroke("M12 4 14.5 9.1l5.5.8-4 3.9.95 5.5L12 17.6 7.05 19.3 8 13.8 4 9.9l5.5-.8z")` | — |
| 5 | `FavoriteOn` | `favorite_on` | mono, via role | `svgFill("M12 4 14.5 9.1l5.5.8-4 3.9.95 5.5L12 17.6 7.05 19.3 8 13.8 4 9.9l5.5-.8z")` | — |
| 6 | `Select` | `select` | duo | `circle(12,12,8)` | `svgStroke("M8.4 12.2 10.8 14.7 15.6 9.6")` |
| 7 | `Pause` | `pause` | mono | `fillPath{roundRect(7.4,6,3.3,12,1.4)}` + `fillPath{roundRect(13.3,6,3.3,12,1.4)}` | — |
| 8 | `View` | `view` | mono | `roundRect(4,4,6.4,6.4,1.6)` + `(13.6,4,…)` + `(4,13.6,…)` + `(13.6,13.6,…)` | — |
| 9 | `Edit` | `edit` | duo | `svgStroke("M5 19h3l9-9-3-3-9 9z")` | `svgStroke("M14 6 17 9")` |
| 10 | `Theme` | `theme` | duo | `circle(12,12,8)` | `svgFill("M12 4a8 8 0 0 1 0 16z")` |
| 11 | `WarnTriangle` | `warn_tri` | mono, status=Warning | `svgStroke("M12 4.4 20.6 19.2H3.4z")` + `svgStroke("M12 9.8v4.3")` + `fillPath{circle(12,16.6,0.95)}` | — |
| 12 | `NotifBell` | `notif_bell` | duo | `svgStroke("M6.5 10.5a5.5 5.5 0 0 1 11 0c0 4.5 2 5.5 2 5.5H4.5s2-1 2-5.5")` | `svgStroke("M10.2 18.5a2 2 0 0 0 3.6 0")` |
| 13 | `NotifOff` | `notif_off` | duo | `svgStroke("M6.5 10.5a5.5 5.5 0 0 1 8.4-4.7")` + `svgStroke("M17.5 12.5c.2 2.4 1.5 3.5 1.5 3.5H8")` | `svgStroke("M4.5 4.5 19.5 19.5")` + `svgStroke("M10.2 18.5a2 2 0 0 0 3.6 0")` |
| 14 | `RecClipCheck` | `rec_clipcheck` | duo (authored; map+wire PR-5) | `roundRect(3.5,6,12,11.5,2.3)` + `svgStroke("M18.5 8.2v7")` | `svgStroke("M12.6 16.8 15.1 19.3 19.6 14.3")` |
| 15 | `Interrupted` | `interrupted` | duo, status-intrinsic (authored; map+wire PR-5) | `circle(12,12,8)` + `svgStroke("M12 4v3M12 17v3")` | `svgStroke("M8 8 16 16")` |

**Modeling notes:**
- **Favorite (4/5):** board draws the star single-channel-accent (off=stroke, on=fill, no neutral layer). Model both outline-only and tint `IconRole.Accent` at the call-site — no fake/empty accent layer. PR-4 toggles state by swapping `Favorite`↔`FavoriteOn`. Call-sites are **role-driven, never status-driven** (a status would recolor the star — documented).
- **WarnTriangle (11):** board has **zero `.ac2`** → mono. Status (locked amber) travels via the map (§6), not a call-site convention.
- **`View` (8) == board `set_grid`:** the board rejected the grid for *Settings* ("collides with View mode"), confirming grid is canonically *View*; replaces `Icons.Default.GridView`.

## 6. `RovaIcons` map changes

- **Flip (no consumers → safe):**
  - `View` → `RovaGlyphs.View` (bare `RovaGlyph`; was `RovaIcon(Icons.Default.GridView)`).
  - `WarningStatus` → `RovaIcon(RovaGlyphs.WarnTriangle.outline, status = IconStatus.Warning)` — **status encoded in the map** so a caller can't forget (codex). Legal because WarnTriangle is mono (`.outline` carries the whole mark) and reuses the existing `RovaIcon(ImageVector, status?)` wrapper + the single-layer `SemanticIcon` overload.
  - `NotificationsSetting` → `RovaGlyphs.NotifBell` (bare `RovaGlyph`; duotone chrome toggle, role-tinted).
- **Add (bare `RovaGlyph`, role-driven):** `Search, Share, Delete, Favorite, FavoriteOn, Select, Pause, Edit, Theme, NotificationsOff (= RovaGlyphs.NotifOff)`. KDoc per concept names the intended consume-with (`Favorite/FavoriteOn`: `role=Accent`; `Delete`: `status=Danger` in destructive contexts).
- **Unchanged:** `Details` (`Icons.Outlined.Info`, consumed), `Play` (`RovaGlyphs.Play`), all existing bespoke `RovaGlyph` vals.
- **No map entry yet:** `RecClipCheck`, `Interrupted` (authored in `RovaGlyphs` only; map + status-encoding land in PR-5).
- **Import cleanup:** remove now-unused `Icons.Default.GridView`, `Icons.Default.WarningAmber`, `Icons.Default.Notifications` imports.

## 7. Tests (same PR — JVM only)

- **`RovaGlyphsTest`** — add the 15 glyphs to the existing `all_glyphs_use_the_24_grid` sweep and the `every_glyph_path_has_a_brush` sweep; per-glyph assertions matching the §5 model column (e.g. `assertNull(Search.accent)`, `assertNotNull(Share.accent)`, `assertNull(Favorite.accent)`, `assertNotNull(Interrupted.accent)`).
- **`SemanticIconSpecTest`** — `statusTint(Danger) == RovaSemantics.error`; `statusTint(Danger) != statusTint(Rec)`.

## 8. Verification (ultracode)

1. **Adversarial board-path-match workflow** — fan out agents, each verifying that one authored glyph's DSL reproduces the board `d=`/element string **exactly** (catch transcription drift, the failure mode that NO-GO'd the first FAB cut). Reference = §5 table + the board source.
2. **codex** final review of the glyph set + seam wiring (initial review folded in above, thread `019edfbc`).
3. **Build gate:** `:app:assembleDebug` (all 46 `check*` green) + `:app:testDebugUnitTest` green. Build WARM; confirm tasks EXECUTED.

## 9. Open question carried forward

Disabled-FAB accent-core (`rec_ring`) — owner GO'd as-is 2026-06-19; unrelated to PR-3, tracked for a later revisit.

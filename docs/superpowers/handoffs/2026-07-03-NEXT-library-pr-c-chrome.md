# NEXT: Library session-list PR-C (chrome) + PR-D (density re-presentation) — fresh-session triggers

PR-B presentation is MERGED (#168 → master `684048bb`, 2026-07-03, device-verified). This file carries the kickoff prompts for the two remaining slices, to be pasted into a fresh session verbatim (edit only if scope changed since).

**Resequencing note (2026-07-03):** PR-C was re-scoped to *mechanical chrome only*. Side-action glyphs, day-header content changes, and the full TalkBack pass moved OUT of PR-C into PR-D, because PR-D's row re-presentation would rewrite them — shipping them twice is waste. PR-C ships what survives PR-D untouched.

---

## Trigger prompt — PR-C (paste into fresh session)

Library session-list **PR-C (chrome)** — kickoff.

This is a "let's build X" task with the design already approved and PR-A/PR-B merged. Do NOT re-brainstorm and do NOT start editing. First run `pwsh scripts/preflight.ps1` and resolve any `[FLAG]`, then invoke the superpowers:writing-plans skill directly (spec exists; this file's scope list is authoritative — it is NARROWER than the spec's chrome section) and write the PR-C implementation plan for my review. After I approve: branch off master, subagent-driven implementation, review-gate (code-review + codex), device-verify on RZCYA1VBQ2H, then PR — no push without my GO.

**Read first (in order):**
1. `memory/project_library_session_list.md` — current state + key decisions
2. `docs/superpowers/specs/2026-07-02-library-session-list-design.md` — the owner-approved spec (chrome items; note this handoff's narrower scope wins)
3. `docs/superpowers/plans/2026-07-03-library-session-list-pr-b-presentation.md` — what PR-B built (interfaces at the top of each task)
4. `docs/adr/0030-library-history-information-architecture.md` — the amended IA contract

**PR-C scope (mechanical chrome only — survives PR-D):**
- **Sticky day headers**: `stickyHeader()` on day-epoch group keys (`LibraryDateLabels` already DST-safe from PR-A); push-up transition between headers; header keys stable across midnight.
- **Midnight ON_RESUME refresh**: relative labels (Today/Yesterday) recompute when the day flips while the app was backgrounded (known pre-existing pattern: `ThermalStatusSignal` fall-dwell also refreshes ON_RESUME).
- **Scrubber fix**: `LibraryScrubber.kt:114-125` — bubble padding exceeds the 48dp rail → negative content width → 1-char wrap; and the bubble is pinned to top instead of riding `thumbY`. Plus 16dp thumb.
- **Density toggle UI**: top-bar action cycling COMFORTABLE/COMPACT (`libraryDensity` pref is live since PR-B — toggle just writes it); a11y `stateDescription`; en+es strings.
- **NOT in PR-C (moved to PR-D):** side-action glyphs, day-header content changes (weekday/count), any row-anatomy change, full TalkBack pass.

**Guardrails (unchanged):** ADR-0030 amendment is the IA contract; ADR-0028 Liquid Glass + `LibraryColors` seam; ADR-0020 WCAG 2.2 AA (≥48dp targets, roles/semantics, reduced-motion); gate `checkLibraryNoManifestWrite` (sidecar only, never manifests); `checkNoHardcodedUiStrings` (new copy → string resources, en+es); verify via `./gradlew :app:assembleDebug` (fires 48 gates) + `:app:testDebugUnitTest` — NOT `:app:lintDebug` (pre-existing VaultAndroidOps:267 NewApi RED); codex MCP peer review for the plan and for code >5 lines; device-verify via PowerShell adb direct (`& "C:\Program Files\platform-tools\adb.exe"` — MCP adb wrapper broken on Windows).

**Device-verify checklist (RZCYA1VBQ2H):** headers stick under scroll + push-up transition clean in both densities; scrubber bubble single-line and rides the thumb; density toggle flips row dims live + persists across cold start; date-label refresh on resume after a day flip (device clock via Settings, restore after); TalkBack sanity on the toggle + headers-not-focus-trapped; latest accent + side actions + PR #164 no-jump unregressed.

**Done when:** suite green (count grows), assembleDebug + 48 gates green, device-verified, PR open (base master).

---

## Trigger prompt — PR-D (paste into fresh session AFTER PR-C merges)

Library session-list **PR-D (density re-presentation)** — kickoff.

Direction is owner-locked via the 2026-07-03 mockup review (local `mockups/library-redesign-density-2026-07-03.html` — gitignored, decisions inlined below per house convention; open it on this machine for visuals). Do NOT re-brainstorm. This slice is **spec-first**: run `pwsh scripts/preflight.ps1`, then write a spec to `docs/superpowers/specs/` from the decision list below (inline every measurement — the mockup file is not in git), present it for my sign-off including the two open rulings, THEN superpowers:writing-plans → plan review → branch off master, subagent-driven implementation, review-gate (code-review + codex), device-verify on RZCYA1VBQ2H, PR — no push without my GO.

**Read first:** `memory/project_library_session_list.md` · PR-B plan (row/aggregation interfaces) · `docs/adr/0030-*` · `docs/adr/0028-*` (glass identity) · ADR-0020.

**Locked decisions (from the mockup + codex reconciliation — presentation only, zero new features):**
1. **Rows, not cards** — full-bleed list rows + inset hairline dividers; per-row glass containers deleted; Liquid Glass identity stays in app bar/sheets/dialogs (ADR-0028 untouched elsewhere).
2. **Time is the scan column** — primary = session title: custom title if set, else the time ("2:41 pm"), 16sp/600, tabular numerals; when a custom title exists the time drops to the head of the meta line. Drop the "Thu · " prefix from row titles entirely.
3. **Day header owns the date** — "Yesterday · Wed, Jul 2" (relative + weekday + absolute) + right-aligned per-day session count and MB. Extends `LibraryDateLabels`; plural strings en+es.
4. **One fact, one place** — duration renders exactly once per row: thumbnail badge on single-topology rows; inside the two side-action labels on DualShot rows (thumb duration badge deleted there). "DualShot" thumbnail badge deleted — side-action presence is the marker. Meta line = "N clips · X MB".
5. **Side actions = tonal buttons** — 36dp visual height, 10dp corner radius (button optics, not chip/pill), tonal ~10% accent fill, tiny portrait/landscape rect glyphs, durations in labels (`library_side_action_label` template stays); 48dp targets via Compose `minimumInteractiveComponentSize`; 8dp apart on a dedicated band under thumb+text.
6. **Accent budget** — theme accent marks changing state only: latest, resume, favorite, selection. Constants (side actions) are tonal. AA ×12 palettes for every new pair (extend `TokenContrastTest`).
7. **Latest = in-timeline accent, not hero** — 3dp leading accent bar + ~7% tint + small LATEST tag + filled Resume button + larger thumb (128×72 vs 98×55) + 3dp resume-progress hairline on the thumbnail. Standard row otherwise; Newest sort only (existing `LatestRowPolicy`).
8. **Favorite = trailing star slot** — never attached to the time (protects the scan column's alignment).
9. **Chrome** — stats line scrolls away with content; filter chips stay pinned (active filter must never scroll off-screen); app bar pinned.
10. **Row minimums** (COMFORTABLE / COMPACT): DualShot ~119dp / ~95dp, single ~75dp / ~59dp; 10dp / 6dp vertical pads; all heights are MINIMUMS — rows grow with fontScale, 24-hour locales, long translations.
11. **Selection mode** — contextual bar, check circle on thumbnail, row tint, side buttons dimmed + disabled; merged row semantics MUST still speak "DualShot" (topology never disappears for TalkBack).
12. **Includes the items moved from PR-C:** side-action glyphs (the rect glyphs above), day-header content, full TalkBack pass.

**Open rulings to present at spec sign-off:** (a) codex wants a small neutral "P+L" topology marker that survives side-button dimming; mockup position = side-action presence + TalkBack description suffice — owner decides. (b) per-day session count in headers adds plural i18n — confirm worth it.

**Guardrails:** same block as PR-C above, plus: NO autoplay anywhere (trust rule); no schema/pref changes expected (`libraryDensity` reused); row semantics stay merged with separate side-action nodes (PR-B pattern).

**Device-verify checklist (RZCYA1VBQ2H):** ≈2× session density vs PR-B build (count rows per screen both densities); scan column aligned (default titles) + renamed session shows custom title with time in meta; duration appears exactly once per row (badge singles / labels duals); latest accent + larger thumb + progress hairline + Resume; favorite star trailing; filters pinned under scroll; selection dims side buttons + TalkBack still says DualShot; AA spot-check on 2–3 extreme palettes (Daylight); PR #164 no-jump; no autoplay.

**Done when:** spec signed off, suite green, assembleDebug + 48 gates green, device-verified, PR open (base master).

---

*Historical once PR-D is merged.*

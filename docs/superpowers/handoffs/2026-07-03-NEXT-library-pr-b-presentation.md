# NEXT: Library session-list PR-B (presentation) — fresh-session trigger

PR-A foundation is MERGED (#166 → master `a672ec4e`, 2026-07-03). This file is the kickoff prompt for PR-B, to be pasted into a fresh session verbatim (edit only if scope changed since).

---

## Trigger prompt (paste below into fresh session)

Library session-list **PR-B (presentation)** — kickoff.

This is a "let's build X" task with the design already approved and the foundation merged. Do NOT re-brainstorm the design and do NOT start editing. First run `pwsh scripts/preflight.ps1` and resolve any `[FLAG]`, then invoke the superpowers:writing-plans skill directly (spec exists, PR-A plan's §"PR-B / PR-C" pointers are the scope) and write the PR-B implementation plan for my review. After I approve the plan: branch off master, subagent-driven implementation, review-gate (code-review + codex), device-verify on RZCYA1VBQ2H, then PR — no push without my GO.

**Read first (in order):**
1. `memory/project_library_session_list.md` — current state + key decisions
2. `docs/superpowers/specs/2026-07-02-library-session-list-design.md` — the owner-approved spec (§3.1–3.4 row anatomy/latest/DualShot, §7 slicing)
3. `docs/superpowers/plans/2026-07-02-library-session-list-pr-a-foundation.md` — the merged foundation: what already exists (interfaces at the top of each task) + the "PR-B / PR-C" scope pointers at the end
4. `docs/adr/0030-library-history-information-architecture.md` — the amended IA contract (Amendment 2026-07-02)

**PR-B scope (from spec §7 + PR-A plan pointers, all in one PR):**
- Single-list `LibraryScreen`: delete the grid branch, `LibraryViewMode`, the view-mode toggle (`LibraryTopBar`), and the `libraryViewMode` pref reads.
- New session row + latest-accent row + DualShot session row (two explicit labeled ≥48dp side actions, row tap = Portrait default made visible; density-driven dimens via `LibraryDensityDimens`). NO autoplay anywhere.
- Wire the merged-but-unwired pure layer into `HistoryViewModel`'s combine: `LibrarySessionAggregator.aggregate` right after `rows.map { toLibraryRow(...) }`; `LatestRowPolicy.latestKey` on the FULL filtered+sorted collection (call `LibraryQuery.collection` with `heroKey = null` once the hero dies — codex note); `_cardPreview` combine slot → `_density` (seed from `RovaSettings.libraryDensity`, coerce unknown → COMFORTABLE).
- Delete hero: `LibraryHeroCard`, `heroFor`, `LibraryAutoplayVideo`, `AutoplayPolicy`, `libraryCardPreview` pref + its Settings row (`SettingsViewModel.kt:37,122`, `SettingsScreen.kt:132,365-366`), `LibraryGridCard`.
- Sidecar lazy-merge for aggregated DualShot rows (spec §3.4): favorite = either side, customTitle = first-non-null portrait-wins, lastPlayedAt = max — via `LibraryMetadataStore`, read-path only (deferred out of PR-A, documented).
- Batch share/delete/favorite resolve per-session via `LibraryRow.sides` (both files). Vault: DualShot sessions stay NOT vault-movable (ADR-0025 status quo).
- Focus-restore simplification: hero slot gone → `FocusRestorePolicy.targetItemIndex` hero-key param path simplifies; keep `shouldScroll` (PR #164 fix) intact.
- Tests land same PR (pure helpers for any new logic; JUnit4 `org.junit.Assert`).

**Guardrails (unchanged from PR-A):** ADR-0030 amendment is the IA contract; ADR-0028 Liquid Glass + `LibraryColors` seam; ADR-0020 WCAG 2.2 AA (≥48dp targets, roles/semantics, reduced-motion); gate `checkLibraryNoManifestWrite` (sidecar only, never manifests); `checkNoHardcodedUiStrings` (new user-facing copy → string resources, en+es); verify via `./gradlew :app:assembleDebug` (fires 48 gates) + `:app:testDebugUnitTest` — NOT `:app:lintDebug` (pre-existing VaultAndroidOps:267 NewApi RED); codex MCP peer review for the plan and for code >5 lines; device-verify via PowerShell adb direct (`& "C:\Program Files\platform-tools\adb.exe"` — MCP adb wrapper broken on Windows).

**Device-verify checklist (RZCYA1VBQ2H):** list renders single-column with day groups; latest accent only under Newest sort; DualShot session shows ONE row, both side actions play the right file; favorite/rename on a DualShot session reflects on the session row; batch delete removes both side files; density toggle pref persists (toggle UI itself is PR-C — flip the pref via Settings/adb if no UI yet); no autoplay; TalkBack sanity on rows + side actions; player→library return does not jump-scroll (PR #164 regression check).

**Done when:** old grid/hero/autoplay code deleted, single-list live with aggregated DualShot rows + latest accent, suite green (count grows), assembleDebug + 48 gates green, device-verified, PR open (base master).

**After PR-B merges:** PR-C (chrome) — sticky day headers on `LibraryDateLabels` with day-epoch group keys, midnight ON_RESUME refresh, scrubber bubble fix (`LibraryScrubber.kt:114-125` — bubble padding exceeds the 48dp rail → negative content width → 1-char wrap; and bubble pinned to top instead of riding thumbY) + 16dp thumb, density toggle in top bar, en+es strings, full TalkBack pass. Device-verify.

---

*Historical once PR-B is merged.*

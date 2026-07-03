# Library session-list redesign (Direction A — Anchored Timeline) + scrubber label fix

**Date:** 2026-07-02 · **Status:** owner-approved design (brainstorming + codex peer review)
**Branch:** `feat/library-session-list` (off master `f24a4edc`, after PR #164 merge)
**Supersedes:** the Library grid+hero presentation layer (ADR-0030 §IA to be amended; data/query/metadata layer unchanged in role)
**Mockups (gitignored):** `mockups/new_uiux/library-session-list-brainstorm.html` (variant explorer), `mockups/new_uiux/library-session-list-v2-comparison.html` (3-layout comparison — tab 2 = the approved layout)

## 1. Problem

The Library reads as a media gallery, not a recording history. Owner jobs-to-be-done: *what did I record today · what's my latest · find yesterday's recording · resume a recent session*. Four defects: weak date hierarchy; "Latest Recording" hero reads as a disconnected widget; grid tiles optimize browse-thumbnails over find-a-session; the date fast-scroll drag bubble (#3) is crushed to one character per line and doesn't track the thumb.

## 2. Research basis (condensed)

- **M3 list-vs-grid rule:** grid only when the image is the item's identity (Photos). Sessions are identified by time/duration/status → list. (m3.material.io/components/lists)
- **Google Recorder:** flat reverse-chron list, time-led rows — the closest analog.
- **Files by Google / serial-position effect:** the item you want is overwhelmingly the newest → recency deserves prominence at top.
- **WhatsApp:** relative timestamps map to episodic memory ("Yesterday evening").
- **NN/g:** sticky (small) date headers answer "when am I?" mid-scroll; recognition over recall.
- **Photos scrubber criticism:** drag label must show the granularity users think in (day), validating fix #3.

Layouts compared: Pure Ledger (safe, but latest gets no prominence) · **Anchored Timeline (chosen)** · Recents Shelf (rejected: duplicates sessions, carousel a11y, reads "media app").

**codex peer-review folds (design-level):** latest row must read as "the top row of a log", not featured content → restrained accent, same information anatomy; **no autoplay** (background-recorded video auto-previewing in public = trust issue, not just motion); DualShot side actions must be explicit labeled ≥48dp targets with the default side visible (no hidden row-tap rule); Resume is a state (saved position), not a sort position; scrubber needs a non-drag a11y path (already exists: slider `setProgress` semantics).

## 3. Design

### 3.1 Structure

`LibraryScreen` renders ONE `LazyColumn` (the `LazyVerticalGrid` branch is deleted). Item order:

1. `hdr-recovery-warn` item (RecoveryAndWarnings — unchanged)
2. Usage line + pinned discovery bar (search + filter chips) — unchanged placement above the list
3. Per day group: `stickyHeader` (day header) + session rows
4. `LibraryScrubber` overlay on the right edge (unchanged placement)

Non-chronological sorts (Longest/Largest) keep the existing header-less flat bucket (`LibraryDayGrouping.groupForSort`); scrubber self-hides (<2 groups); no latest accent. Chronological Oldest: day groups, no latest accent (anchor applies to Newest only).

**Deletions:** `LibraryGridCard`, `LibraryHeroCard`, `LibraryAutoplayVideo`, `AutoplayPolicy`, `LibraryQuery.heroFor` + hero de-dup, GRID/LIST `LibraryViewMode` + `RovaSettings.libraryViewMode` + top-bar view toggle, `RovaSettings.libraryCardPreview` + its Settings row (autoplay dies wholesale — hero was its last default-on consumer).

### 3.2 Session row (M3 two-line list item)

- Leading thumbnail: 104×60dp (Comfortable) / 84×50dp (Compact), 12dp radius, duration chip bottom-right.
- Title = time of day ("2:41 PM") — `SmartTitle` WHEN/WHAT split survives; custom rename still overrides title.
- Meta line: `N clips · size` (+ exceptional-only status badges per existing `StatusBadgePolicy`; favorite star trailing).
- Row min-height 64dp / 56dp; all targets ≥48dp; row = one merged semantic node (via `TileSemantics`, updated).
- Row tap → player (side-default for DualShot, §3.4). Long-press → existing item sheet. Selection model (batch bar, pending delete) carries over unchanged.

### 3.3 Latest row (the anchor)

First row of the list when **sort = Newest** (filter-aware: first row of the *visible* collection, matching old `heroFor` newest-match semantics but without a separate surface):

- Tinted container (identity family from `LibraryColors` — theme-aware), hairline accent border, 16dp radius, inset 14dp horizontal.
- Thumbnail 128×74dp; "LATEST" eyebrow (labelSmall, accent2); same title/meta anatomy as every row.
- Explicit pill: **Resume · m:ss** when a saved playback position exists for the session (resume seam from #137, `lastPlayedAt`/positions), else **Play**. Pill is its own labeled target; row tap does the same thing (pill is the visible affordance, not a divergent action).
- **No autoplay.** No media surface — static thumbnail only.
- Contrast: tinted container + eyebrow pass `TokenContrastTest` across all 12 palettes.

### 3.4 DualShot: one row per session

`LibraryRowMapper` gains a pure aggregation step: the two per-side rows collapse into one session row carrying both sides.

- Row shows `DualShot` badge; meta = combined clips/size of the session (per-side durations shown on the side actions, so no N×2 inflation — consistent with the `SessionDurations.forRow` fix).
- Two explicit side actions under the meta line: "▯ Portrait · 1:30" and "▭ Landscape · 1:30" — each a separately-focusable, labeled, ≥48dp-target button that plays that side. Row tap plays **Portrait** (primary side); the side actions make the choice visible — no hidden rule.
- **Identity:** the session row keys on the sessionId-canonical `RecordingIdentity` (`session:<id>` — same seam as player resume). Single-mode rows keep their current `stableKey`.
- **Sidecar migration (lazy):** legacy per-side `LibraryMetadataStore` entries merge on read — favorite = either side favorited; customTitle = first non-null (portrait wins ties); lastPlayedAt = max. Writes go to the session key. No store schema bump; `prune` keeps dropping orphans. `checkLibraryNoManifestWrite` untouched (sidecar only).
- Batch ops (share/delete/favorite) operate per-session = both sides. Share of a DualShot session shares both files (existing multi-uri share path). Vault keeps the existing rule: DualShot sessions are NOT vault-movable (status quo from B5); the session row simply hides/disables the Vault action exactly as the per-side rows did.
- Player entry from a side action passes that side's uri + session identity (resume positions are already per-side via `positionsBySide`).

### 3.5 Date hierarchy

- Compact sticky headers (~36dp): left "Today · 2 Jul" (relative bold + absolute quiet), right "3 sessions · 96 MB". `semantics { heading() }`.
- Relative labels: Today / Yesterday / weekday name (2–6 days back) / date beyond. Pure helper (extends `LibraryDayGrouping` labeling), JVM-tested incl. locale/tz.
- **Midnight stale-label fix folded in:** the label base timestamp recomputes on ON_RESUME (existing lifecycle observer) — closes the known latent "day labels stale across midnight" issue.
- Sticky behavior: `LazyColumn` `stickyHeader` (ExperimentalFoundationApi opt-in). No push-animation styling; headers replace flatly (no extra motion).

### 3.6 Scrubber fix (#3)

`LibraryScrubber.kt`:

- Bubble escapes the 48dp rail: `wrapContentWidth(unbounded = true)` (or equivalent layout that doesn't clamp to parent width) so the label lays out at its intrinsic width — no more one-char wrap.
- Bubble offsets vertically to track `thumbY` (same computed offset as the thumb dot), staying clamped inside the rail bounds.
- Thumb dot visual 24dp → **16dp** (owner request). The gesture target is the full-height 24dp-wide rail — unchanged, so touch-target a11y is unaffected. `progressBarRangeInfo`/`setProgress` slider semantics and the separate polite live-region stay byte-identical in behavior.
- Label stays day-granularity (group label) — matches the research finding (day, not year).

### 3.7 Density setting

- `RovaSettings.libraryDensity`: `COMFORTABLE` (default) | `COMPACT`, persisted string pref; replaces the retired `libraryViewMode`.
- Toggle in the top-bar slot the grid/list toggle vacates: icon button cycling density, `stateDescription` announces current mode.
- Pure `LibraryDensityDimens` (thumb size, row min-height, paddings) — JVM-tested; composables read dimens from it only.

### 3.8 Motion & focus

- Press feedback: existing `PressFeedback`/`pressScale` on row containers (reduced-motion → unity). No new infinite animations (`checkA11yAnimationGated` unaffected).
- Focus restore (PR #164) carries over and **simplifies**: hero lazy-key special-casing (`hero-` prefix normalization) is deleted; `pendingFocusKey` is the row key directly. `FocusRestorePolicy.shouldScroll` unchanged.
- Scrubber idle-fade behavior unchanged.

### 3.9 Scale

- Stable keys on every item (session identity keys). Sticky headers keyed `hdr-<label>` (duplicate-label invariant already pinned by `LibraryDayGroupingTest`).
- All strings/labels precomputed in pure helpers (mapper/`SessionCaption`), not formatted per recomposition.
- Thumbnail pipeline (`ThumbnailDiskCache`) unchanged. No autoplay → fewer decoders, strictly less runtime load than today.

### 3.10 Accessibility (ADR-0020 AA)

- Day headers: heading semantics. Rows: merged descriptions incl. status ("2:41 PM, 6 clips, 48 megabytes, auto-stopped"). DualShot side actions: individually labeled ("Play portrait side, 1 minute 30 seconds").
- Latest pill labeled ("Resume from 1:12" / "Play latest recording").
- Density toggle: `stateDescription`. All targets ≥48dp (`checkA11yTargetSizeToken` / `checkA11yClickableHasRole` gates apply).
- Scrubber: slider semantics = the non-drag path (TalkBack volume-key adjust); polite live-region announces landed day.
- New tinted surfaces through `TokenContrastTest` (12 palettes).

## 4. What does NOT change

Data/query layer roles (`LibraryQuery` collection/sort/filter, `LibraryDayGrouping` buckets, `LibraryMetadataStore` sidecar, `ScrubberIndex` math), discovery bar (search/chips), sort sheet, selection + batch bar + pending-delete UNDO, item sheet, rename dialog, vault flow, recovery header, usage line, `checkLibraryNoManifestWrite`, focus-restore architecture, player handoff (`PlayerPosterHandoff`).

## 5. ADR impact

Amend **ADR-0030** (Library IA): grid view removed (single list presentation); hero replaced by in-timeline latest-row accent; DualShot = one session row with explicit side actions (supersedes two-rows-per-side); density setting added; autoplay removed. Amendment lands in PR-A before code. No gate mechanism changes; no new gate (presentation-layer invariants covered by existing a11y gates + JVM tests).

## 6. Testing

JVM (same-PR): mapper aggregation (DualShot collapse, sidecar merge rules, identity keys), latest-row eligibility (sort/filter matrix), relative-date labeling (locale/tz/midnight), `LibraryDensityDimens`, scrubber geometry additions to `ScrubberIndexTest` if any. Existing suites must hold: `LibraryDayGroupingTest` (incl. duplicate-label invariant), `FocusRestorePolicyTest` (hero cases updated/removed), `LibraryQueryTest` (heroFor tests removed with the API). Baseline 1241 tests: net grows; nothing skipped. Gates: 48 + `assembleDebug` green per PR (NOT lintDebug — pre-existing NewApi RED). Device-verify on RZCYA1VBQ2H at PR-B and PR-C (scroll rhythm, sticky headers, DualShot row, scrubber bubble tracking, TalkBack pass).

## 7. Delivery (stacked PRs on `feat/library-session-list`)

- **PR-A — foundation:** ADR-0030 amendment; mapper session-aggregation + identity/sidecar merge; pure helpers (`LibraryDensityDimens`, relative-date labels, latest eligibility); prefs swap (`libraryDensity` in, `libraryViewMode`/`libraryCardPreview` out). PR-A is pure-layer only — no UI wiring, no visual change; the old UI keeps rendering off the un-aggregated path until PR-B swaps it.
- **PR-B — presentation:** single-list `LibraryScreen`, session row + latest row + DualShot row, deletions (grid/hero/autoplay), focus-restore simplification. Device-verify.
- **PR-C — chrome:** sticky day headers + midnight fix, scrubber fix (#3) + 16dp thumb, density toggle, polish. Device-verify + TalkBack pass.

Merge train per house rule: merge without branch-delete → rebase next onto master → retarget → delete.

## 8. Open items deferred (not in scope)

Inline expand-to-preview (Voice Memos pattern) — candidate later slice. Jump-to-date affordance beyond the scrubber — revisit if session counts make the rail insufficient. Search-by-content — out of scope.

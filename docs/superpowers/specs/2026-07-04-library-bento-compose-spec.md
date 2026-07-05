# Library Bento — Compose implementation spec

Sources of truth, in order:
1. `docs/design/library-bento.html` **v3.2 FROZEN** (pixel-match target; open in a browser — the 12-swatch
   row previews every palette)
2. ADR-0030 amendment 2026-07-04 (behavioral invariants)
3. This spec (exact values inlined per the freeze-design convention + Compose transcription mappings)

Scope: presentation layer of the Library screen only. No manifest schema change, no service change, no player
change. `checkLibraryNoManifestWrite` binds all new code; JVM-only tests; `:app:assembleDebug` +
`:app:testDebugUnitTest` verify (NOT `lintDebug` — pre-existing NewApi RED).

## 1 · Frozen values (from the v3.2 HTML — do not re-derive)

### Grid
- 6 columns, 10dp gap, 16dp page gutter. Day sections stack vertically; 24dp between days.
- Month divider between months: centered caps label, 10.5sp / 700 / 0.22em tracking / textDim,
  padding 22dp top / 12dp bottom.

### Recency tiers (day age `d` = whole days since the day's local midnight)
| tier | when | row patterns (span list @ row height dp) | single-leftover fill |
|---|---|---|---|
| featured | d ≤ 1 | hero `[6]@208` (first slot ONLY when the day has ≥3 sessions) · `[3,3]@152` · `[4,2]@164` · `[2,4]@164` | `[6]@192` |
| standard | 2 ≤ d ≤ 6 | `[4,2]@148` · `[3,3]@128` · `[2,2,2]@104` · `[2,4]@148` | `[6]@148` |
| archive | d ≥ 7 | `[2,2,2]@92` · `[3,3]@92` | `[6]@108` |

Row planner (pure Kotlin, port of `buildRows`): walk the day's sessions in chronological (newest-first) order;
`rot` starts at `d` and increments per placed row; candidates = tier rows with `len ≤ remaining`; rotate the
candidate list by `rot % candidates.size`; pick the first variant giving every upcoming DualShot a span ≥ 3
(fallback: first rotated candidate); a single leftover takes the fill row. Chronological order is NEVER
re-sorted. Rotation keys on day AGE so filter/search never reshuffles a day's pattern.

### Radius scale (dp)
`r-sm 10 · r-md 14 · r-tile 16 · r-lg 18 · r-sheet 26`

### Motion (single source; exits ≈ 70% of enters)
`micro 120ms · small 200ms · container 300ms · exit 220ms`; standard ease `cubic-bezier(.2,.8,.2,1)`;
spring pop for the selection check `cubic-bezier(.34,1.56,.64,1)` (Compose: spring w/ overshoot or keyframes).
Boot-only entrance stagger: 30ms/item, translateY 14dp + scale .97 → identity over 500ms; runs once per screen
entry, never on later data changes. All non-essential motion gated by reduced-motion (ADR-0020 / `ReducedMotion`
seam); the header wash toggle becomes instant under reduced motion.

### Type scale (sp; tabular-nums on every numeric)
`10.5 caps (month divider, endcap) · 11 media pill · 11.5 meta/stats · 12.5 body/facts · 13.5 control (chips,
vault) · 14 action/day-header title · 15 emphasis/empty-title · 18 sheet title · 21 page title`

### Day header (ground, not chrome — ADR amendment §4)
- Anatomy identical pinned/unpinned: padding 8dp vertical / 16dp horizontal; title 14sp/700/**lineHeight 21sp**;
  meta `"{n} recording(s) · {dur}"` 11.5sp/**lh 21sp**/textDim; box height locked 37dp.
  Selection mode adds a 24dp select-all circle in a 48dp target, right-aligned.
- Pinned wash (the ONLY state difference): the page's own top background color, solid from 0→31dp then linear
  dissolve to transparent at 63dp total height (extends 26dp beyond the box, draws under header content, above
  tiles). Fade in 120ms / out 200ms. **Pinned state must be computed synchronously with layout/content changes**
  — a pinned header must never render a frame without its wash (v3.2 codex High).
- Day identity/labels: PR-C substrate carries forward (day-epoch keys, Today/Yesterday/date labels, midnight
  ON_RESUME re-stamp).

### Tile (shared by single + DualShot)
- Container: radius 16dp; 1dp border white@.08 (media-edge-top); elevation e1 (dark: `0 6 22 black@.35`,
  light theme: `0 4 14 ink@.10`); press scale .97 (interruptible, 120ms).
- Placeholder while thumbnail loads: 140° gradient surfaceHi → surface (both single tiles and duo panes —
  one loading language).
- Bottom legibility scrim (media-relative, on every pane): vertical gradient `black-ish(6,3,8)@.16 → transparent
  26% → transparent 52% → (6,3,8)@.58 100%`.
- DualShot diptych: two panes split 50/50 with a 2dp seam of `#060308` (--media-seam). Each pane is its OWN
  clickable (Role.Button): tap side = play that side; Portrait ALWAYS the left pane. In selection mode both
  panes toggle the session.
- NO hover ken-burns on Android (desktop-only affordance in the spec — documented mapping).

### Tile overlays (live at tile level, whole across the duo seam)
- **Meta pill** (bottom-left 8/8): 11sp/600 tabular, media-ink white@.95 on media-scrim rgba(6,3,8,.62),
  1dp border white@.10, pill radius, padding 3dp/8dp. Content: `[orientation glyph(s)] time [· dur]` —
  duration only on span ≥ 3 tiles, dimmed white@.68. Blur behind the pill in the HTML is decorative;
  Compose renders the solid scrim only (documented mapping — the .62 scrim alone passes worst-case AA).
- **Orientation glyph** (in-pill, leading): 11dp box, 12×12 viewport, rounded-rect outline stroke 1.4,
  P = x3.4 y1 w5.2 h10 rx1.6, L = transposed. Single tile: its orientation. DualShot: ▯▭ pair (pane order),
  2.5dp gap. Full media-ink (not dim). Unknown orientation (legacy rows): omit the glyph — pill shows time only.
- **Latest chip** (top-left 8/8, unfiltered view only, newest session): caps 10sp/700/0.1em, accent-fill bg /
  accent-ink text (see §Palette), padding 4/9, pill.
- **Status badge** (exceptional only — ADR-0030 decision 3 + AUTO_STOPPED): same anatomy slot as the latest
  chip; badge wins the slot, latest chip drops when both apply. Colors/glyphs = shipped `LibraryBadge`
  taxonomy verbatim (locked status colors), backed by the media scrim (structural-scrim rule).
- **Favorite**: gold star (locked #FBBF24) in a 24dp media-scrim dot, top-right, indicator-only on touch
  (favoriting happens via selection batch or details); hidden in selection mode.
- **Selection check** (bottom-right 8/8): 22dp circle; unselected = white@.16 fill + 1.5dp white@.7 border;
  selected = accent-fill + accent-ink check; spring scale-in. Selected tile: scale .94 + 2dp accent-fill ring
  + media-dim rgba(6,3,8,.35) overlay.

### Chrome
- **Top bar**: back · "Library" 21sp/700 · search · select-mode entry. Density toggle REMOVED.
- **Stats line**: `"{n} recordings · {size}"`; under filter/search `"{x} of {y} recordings"`; 11.5sp textDim.
- **Filter chips** (All / Favorites / DualShot): scroll away with content; pill, min 48dp target, 13.5sp/600;
  rest = 5% text-ink fill + edge border; active = accent-fill bg / accent-ink text.
- **Vault door row**: quiet full-width row above the timeline (min 48dp): lock glyph, "Private Vault",
  "{n} recordings" count, chevron. Destination, never a filter.
- **Selection bar** (replaces top bar content while selecting): close · "{n} selected" (live region) ·
  All toggle · info (enabled iff n==1) · favorite · vault · delete. Solid chrome surface (surfaceHi@94%),
  radius 18, e2.
- **Date scrub rail**: auto-hiding right-edge thumb (48dp target) + date bubble while dragging; shows on
  scroll when content is meaningfully scrollable, hides after 1.4s idle; slider semantics with
  valuetext = day label; keyboard/rotary steps exactly one day. Supersedes the PR-C scrubber anatomy
  (bubble/thumb spec: bubble 12.5sp/700 chrome-solid pill offset from thumb).
- **Toast/undo**: bottom inset 16dp, radius 16, chrome surface, action in accent-text, ≥48dp action target;
  auto-dismiss 3.2s / 5.2s with action.
- **Empty states**: title 15sp + body 12.5sp textDim, per-filter copy (no matches / no favorites /
  no DualShot / no recordings).
- **Endcap**: `"— {n} RECORDINGS —"` caps 10.5sp textFaint.

### Details sheet (selection-mode ⓘ, exactly one selected)
Modal bottom sheet: radius 26 top, surface gradient (surfaceHi→surface), grab handle, explicit 48dp close
button (media-scrim dot). Hero 16:9 (DualShot: two tappable panes with Portrait/Landscape labels + per-side
play; single: center play disc with AA-resolved gradient). Title 18sp/700 with inline rename (pencil, text
field, Enter commits / Esc cancels locally). Facts prose 12.5sp/1.7: date · start–end · duration · clips ·
size · **Portrait/Landscape/DualShot**. Action rows ≥50dp: favorite toggle, move to vault, **Share**
(v3.2.1 — content-URI handoff via the shipped `shareItems` path; export remains banned, ADR-0030 §1).
Danger zone visually isolated: delete with Undo toast. Sheet actions that remove the session
(vault/delete) drop it from the selection before closing.

### Palette derivation (Compose = the real thing)
- All semantic colors from the active `RovaPalette` via the existing theme engine; NOTHING hardcoded.
- `accent-fill`/`accent-ink` (chips, latest chip, check, rail-drag) and the sheet play-disc gradient pair
  resolve through **`DialogActionColors.resolve`** (the HTML ran a JS port; Compose calls the real one).
- accent-as-text = accent 72% mixed toward textHigh; danger-text = danger 62% toward textHigh.
- State layers keyed to text ink (flip automatically on Daylight): fill-1 5%, fill-2 8%, press 12%,
  hairline 6% of textHigh.
- chrome-solid = surfaceHi 94%; elevations: dark `e1 0/6/22 black.35 · e2 0/12/34 .5 · e3 0/18/60 .55`,
  light `e1 0/4/14 ink.10 · e2 0/10/28 .16 · e3 0/16/44 .20`.
- Media-relative constants (NEVER themed): scrim rgba(6,3,8,.62) · edge white.10 · edge-top white.08 ·
  ink white.95 · ink-dim white.68 · dim rgba(6,3,8,.35) · seam #060308 · modal scrim black.50.

### A11y (ADR-0020 AA; gates: checkA11yTargetSizeToken, checkA11yClickableHasRole, checkA11yAnimationGated)
- Tile labels: `"Play [portrait|landscape recording, ]{day} {time}, {duration}[, favorite][, latest
  recording]"`; selection mode swaps verb to `Select`. Duo: pane buttons `"Play {side} side, …"`; the pair
  grouped with a `"DualShot, …"` description.
- Touch targets: HTML mocks 44px; **Compose uses the app's ≥48dp token** (documented mapping — targets grow,
  visuals unchanged).
- Day headers: heading semantics, not focus-trapped; select-all circles labeled per day with state.
- Rail: slider role, valuetext = day label; selection count announced politely; toast is a status region.
- Strings: ALL new copy in `values/strings.xml` + `values-es/strings.xml` (checkNoHardcodedUiStrings);
  user copy says "recordings", never "videos".

## 2 · Transcription mappings (documented, not design decisions)
1. Hover-only affordances (ken-burns zoom, desktop star-on-hover) do not exist on touch — omitted.
2. Meta-pill backdrop blur → solid scrim (AA holds by the scrim alone).
3. 44px mock targets → ≥48dp app token.
4. Tile→player ghost-morph mocks the *boundary*; navigation uses the app's existing route transition
   (morph = optional later polish, NOT in this cycle).
5. The HTML's FLIP list morphs → Compose `animateItem` placement + fade-ins, reduced-motion gated.
6. Web `content-visibility` / IO stuck-detection → LazyColumn + `stickyHeader`; the wash state derives
   synchronously from lazy-list layout info (first-visible header offset), not from an async observer.

## 3 · Current-code mapping (from the 2026-07-04 codebase exploration, master d0f10230)

Screen = `ui/library/LibraryScreen.kt` (`fun LibraryScreen` :114) on nav route `"history"`; ViewModel =
`ui/screens/HistoryViewModel.kt` (`libraryUiState = combine(items, hasLoaded, _sidecarRevision, _density)`).
Play path: `LibraryScreen.play()` → `onOpenPlayer(sessionId, side)` → route `player/{sessionId}?side={side}`
(DualShot default = portrait side; pane taps pass `VideoSide.PORTRAIT/LANDSCAPE` explicitly).

**DELETE (superseded by the frozen design):**
- Density: `RovaSettings.libraryDensity` (pref key `"library_density"`), `HistoryViewModel._density` /
  `readDensity` / `toggleDensity` / `refreshDensity`, top-bar toggle (`LibraryTopBar` GridLayout icon +
  `stateDescription`), `LibraryDensityDimens` + its test, `library_density_*` strings (en+es).
- Sort UI: `LibrarySortSheet`, top-bar Sort icon, `library_sort_*` strings; VM pins `LibrarySort.NEWEST`
  (`LibrarySort`/`groupForSort` pure code + tests stay — API unchanged, UI writer gone).
- Latest-row accent variant machinery: `LibraryColors.latestContainer/latestEdge/latestEyebrow` consumers in
  the row composable, `library_eyebrow_latest` usage, `library_latest_resume` (Resume pill) — replaced by the
  LATEST chip on the tile.
- `components/LibraryListRow.kt` (row anatomy) as the list body — superseded by tiles. Legacy orphan
  `ui/screens/LibraryRow.kt` (VideoItem composable, `history_*` strings): verify unwired, then delete.
- `LibraryRenameDialog` IF the sheet's inline rename fully replaces it (frozen behavior).

**KEEP (substrate carries forward — do not rewrite):**
- Identity/aggregation: `RecordingIdentity` (sessionKey `"session:<id>"`), `LibrarySessionAggregator`
  (portrait-first sides, size-sum/duration-max), `LibrarySessionKeys.expand`, `SessionSidecarMerge`.
- Sidecar: `LibraryMetadataStore` / `LibraryMetadataEntry` / codec / `PruneKeepSet` (favorite, customTitle,
  positionsBySide; merge-on-write legacy aliasing).
- Day substrate: `LibraryDayGrouping` (epoch keys `dayEpochMillis`), midnight ON_RESUME re-stamp pattern,
  `LibraryDateLabels.dayEpoch`.
- Policies: `LatestRowPolicy` (feeds the chip), `StatusBadgePolicy` + `LibraryBadge` + reason-aware
  `LibraryIconSpec.badgeGlyph` (AUTO_STOPPED reuses locked `IconStatus.Interrupted`), `FilteredEmptyPolicy`,
  `LibraryStatePolicy`, `FocusRestorePolicy` (#164), `PendingDelete` (deferred delete + UNDO),
  `SelectionReducer`, `LibraryQuery` (filters; `heroKey` stays null).
- Rail math: `ScrubberIndex` (segments/nearest/bubbleTopPx) — re-fed with bento ROW counts per day.
- Thumbnails: `VideoFrame` + `ThumbnailDiskCache` + `ThumbnailCacheKey`.
- Orientation: `LibraryOrientation` + `OrientationResolver` (see §4). Overlay raw material:
  `components/LibraryBadges.kt` (`OverlayPill`/`StatusBadgePill`/`OrientationFramePill`/`CaptionBar`) — adapt.
- Recovery cards + warning strip (leading LazyColumn item `"hdr-recovery-warn"`) — carried forward verbatim
  above the timeline (safety UX, out of the mock's scope by design).
- Share: `LibraryScreen.shareItems` (ACTION_SEND_MULTIPLE) — surfaces via the details sheet's Share row.

**MODIFY:**
- `LibraryScreen` body: usage line / search field / chips move from the pinned Column INTO the LazyColumn as
  leading scrolling items (frozen: they scroll away; search field lives in the top bar). Vault entry moves
  from the top-bar icon to the quiet door row above the timeline. List body becomes day-sectioned bento rows
  (one LazyColumn item per planned bento row; tiles keyed inside).
- Day header label source: switch from `HistoryRowFormatters.formatGroupHeader` (hardcoded English) to
  resource-aware `LibraryDateLabels.headerLabel` (`DayHeaderKind` → `library_day_today` / `library_day_yesterday`
  / weekday / absolute; new en+es strings) — fixes a real i18n gap while re-implementing the header.
- Header count/duration meta: `"{n} recording(s) · {dur}"` replaces the size-total (`library_day_size_total`
  retires; new plural + duration strings).
- `LibraryScrubber`: keep slider-on-rail semantics + separate polite live-region + `ScrubberIndex` math;
  re-skin anatomy to the frozen rail (48dp chrome tab thumb + date bubble, 1.4s idle hide).
- `LibraryItemSheet` → the frozen details sheet (selection-ⓘ entry, hero panes, inline rename, facts prose,
  Share row, danger zone). `LibrarySelectionTopBar` gains All + info(=1) + fav/vault/delete per frozen selbar.
- `LibraryColors`: retire latest-row fields; add the AA-resolved accent-fill/ink pair via the real
  `DialogActionColors.resolve` (chips / latest chip / check / rail-drag) + state-layer/hairline derivations.

**Known shipped-behavior deltas to surface at review (frozen design drops them):**
1. Batch Share (selection bottom `LibraryBatchBar` has Share; frozen selbar has none — share becomes
   per-session via the details sheet). 2. `LibrarySessionConfigDialog` ("view settings") — not in the frozen
   sheet. 3. Bottom batch bar replaced by the frozen top selection bar. Owner rules at pixel-match if any
   should return (each is additive later).

## 4 · Orientation data source (RESOLVED — derivable today, no new persistence)
`LibraryRow.orientation: LibraryOrientation?` already exists, resolved at map time by
`OrientationResolver.resolve(side, thumbWidthPx, thumbHeightPx)` (`LibraryRowMapper.map` :62):
DualShot → authoritative `VideoSide`; single → rotation-corrected thumbnail pixel dims (taller = PORTRAIT);
square/unknown → null. Contract: null orientation ⇒ glyph omitted (pill shows time only) — never guessed.
DualShot tiles don't read `row.orientation`; the ▯▭ pair is structural (pane order, Portrait left).

## 5 · Non-goals (this cycle)
Player changes, autoplay of any kind, export UI, DualShot vault-move (ADR-0025 status quo), sort controls,
tile→player shared-element morph, thumbnail disk-cache redesign.

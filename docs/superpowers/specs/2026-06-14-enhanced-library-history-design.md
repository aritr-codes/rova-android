# Enhanced Library & History UI — Design Spec

> **Date:** 2026-06-14 · **Status:** Draft (awaiting owner spec-review)
> **Track:** UI/UX Modernization (`docs/BACKLOG.md`) · **Pipeline:** brainstorm → **spec** → plan → build → device smoke
> **Author:** brainstorming session (owner-directed), informed by a 7-agent discovery pass + a 4-agent
> export/perf/feature research pass.

---

## 1. Problem & goal

The Library/History surface — the screen where users browse **all** their recordings — has had no visual
modernization pass. It carries only the `#100` nav state-retention fix and deferred a11y focus rows. Today it
is a single screen (`HistoryScreen.kt`, route `"history"`, ~1.1k lines) that already does a surprising amount
(date grouping, multi-select share/delete, two-stage thumbnail load, recovery cards) but in pre-Liquid-Glass
chrome with gaps at scale.

**Goal:** redesign Library/History to the shipped Liquid Glass design language (ADR-0028, 12-theme system),
adding richer thumbnails, hero+grid layout, sort/filter/search, multi-select batch actions, favorite + rename,
exceptional-state badges, polished empty/loading, and a disk thumbnail cache — while folding in the deferred
accessibility debt (rows 21, 23, 32). WCAG 2.2 AA by default (ADR-0020).

This is a **feature**, JVM-tested, landing as a sequence of stacked PRs.

---

## 2. Locked decisions

Owner-ratified 2026-06-14 (decisions A–F + four scope forks):

| # | Decision |
|---|---|
| **A. Layout** | **Hero + Grid** with a **grid/list toggle**. Hero highlights the newest recording and exposes quick actions (Play / Favorite / Share) *without* entering selection mode. |
| **B. Batch actions** | **Share · Move-to-Vault · Favorite · Delete.** **Export is CUT** (see §3). |
| **C. Discovery** | **Search + Sort + Filter all ship in this work** (no deferral of search). On-device only. |
| **D. Metadata** | Add **Favorite (★)** and **Rename (custom title)**, with an auto-derived smart title when unnamed. Stored in a **sidecar store**, *not* the manifest (§6 — codex-driven correction). |
| **E. Status badges** | Cards stay visually clean. **No "Complete".** Surface only **exceptional** states (Recovered / Interrupted). Export-pending, if ever shown, lives in detail views — moot now that Export is cut. |
| **F. Player a11y** | Include the **SegmentedTimeline** accessibility work (row 21: `progressbar` role + per-cell labels) in this work to close the related debt. |
| Trash/undo | **Defer full Recently-Deleted bin to v2.** v1 keeps confirm-dialog delete **plus a Snackbar UNDO** for the just-deleted batch (cheap). |
| Storage mgmt | **Lightweight v1:** size per card + per-day header totals + sort-by-size. No dedicated management screen (v2). |
| PR shape | **Stacked slices, one PR each** (§11). |
| Vault UX | **Keep the vault as a separate, auth-gated destination** (matches B5 + locked-folder convention). Main grid never shows vaulted items; redesign only re-skins the existing vault entry to glass. |

---

## 3. Why Export is cut (decision B rationale)

Owner asked for explicit justification-or-removal. Investigation verdict: **remove — redundant.**

Every **successfully finalized** session is already in a user-visible location the moment `exportState=FINALIZED`:

| Export tier | Physical location at FINALIZED | User-visible? | Re-export needed? |
|---|---|---|---|
| `TIER1_API29_PLUS` | MediaStore `Movies/Rova` (published via `IS_PENDING=0`) | Yes — gallery | No |
| `TIER2_API26_28` / `TIER3_API24_25` | `Movies/Rova/<name>.mp4` + `MediaScannerConnection` | Yes — gallery | No |
| `SAF_DESTINATION` (ADR-0024) | user-picked folder (SAF doc URI) | Yes — their folder | No |
| **VAULTED** (ADR-0025) | app-private, deliberately hidden | No — by design | Move-**out** (un-vault) ≠ export |
| **FAILED / MUXING / COPYING** | no public copy yet | N/A | Recovered by `ExportRecoveryRunner` at cold launch only |

Supporting facts:
- **Share ≠ save.** `HistoryScreen` Share builds `ACTION_SEND_MULTIPLE` with content-URIs; it copies nothing to
  the gallery — it hands a URI to another app.
- `ExportPipeline.export()` is **only** callable from `RovaRecordingService.performMerge()` (record-time) and
  `ExportRecoveryRunner` (cold-launch recovery). **No UI-reachable re-export entry exists**, and building one
  (re-mux + re-publish, tier frozen at session start) would be a large new path solving a problem nobody has.
- The genuine "save a file vs share to an app" distinction the creator-app research raised maps onto **vault
  move-out**, which already exists as its own action.

→ Export is absent from both the batch bar and the per-item menu. (`checkExport*` gates are unaffected — they
guard the record-time pipeline, which is untouched.)

**Edge case (codex):** if a session's **public** media URI/path is later missing/unreadable (user deleted it
from the gallery, moved an SD card, lost permission) and no app-private source remains, re-export is impossible
anyway — so the right UX is **"file missing → remove from Library"**, not an Export button. The Library load
already validates artifact existence (`MediaFileValidator`); a missing-public row is surfaced as unavailable and
offered for removal, not re-export. (Vault sessions retain an app-private copy and leave only via the existing
move-out.)

---

## 4. Information architecture

- **One surface, three view modes:** the main Library (Hero + Grid by default, toggleable to List) and the
  separate auth-gated **Vault** destination (re-skinned only). Recovery cards + History warning strip keep their
  current pre-list placement.
- **Unit = one recording session** (segments already merged). A P+L (DualShot) session is one card with a `P+L`
  badge. Vaulted sessions never appear in the main grid.
- **Default sort:** newest-first (the core loop is "the thing I just recorded").
- **Grouping:** by capture day (`Today` / `Yesterday` / `MMM d, yyyy`) sticky headers — extend existing
  `HistoryRowFormatters`. Headers gain a per-day **total size** and a **select-all** affordance (in select mode).

---

## 5. Layout & components

### 5.1 Hero + Grid (default)
- **Hero card** at top: newest session, larger tile, play affordance, quick-action row (Play / Favorite / Share)
  that does **not** enter selection mode. Eyebrow "Latest recording" + derived title + meta line.
- **No duplication (owner #4):** the hero's session is **excluded from the grouped grid/list below** so the same
  recording never appears twice. The day group it belonged to drops it (and is omitted if it becomes empty). The
  exclusion is by `stableKey`, computed in the pure `LibraryQuery` layer so list/grid/search all agree. (In List
  mode with the hero hidden, or when filtered/searched, the newest matching row is the hero source — never
  double-listed.)
- **2-column grid** below, grouped by day. Each `GridCard`:
  - center-cropped keyframe thumbnail (opaque content plane — never glass-on-thumbnail),
  - **duration** badge (mandatory; sum of `segments[].durationMs`), bottom-right,
  - **exceptional status** badge only (Recovered / Interrupted), top-left, else nothing,
  - `P+L` badge if `captureTopology=DualShot`,
  - caption scrim (derived/custom title + clips · size) with a **structural dark scrim** guaranteeing ≥4.5:1
    over any frame.
- **Grid/List toggle** in the top bar. List mode = today's richer per-row metadata layout, re-skinned to glass
  cards.

### 5.2 Selection & batch
- Enter via **long-press** on any tile (and a "Select" affordance). `toggleable` per tile with
  `role` + `stateDescription` ("selected"/"not selected") — never a checkmark visual alone (drag-select is
  invisible to TalkBack).
- **Glass contextual top bar** replaces the title bar: close (✕), live **"N selected"** count (polite
  live-region), select-all. Per-day **select-all** from the date header.
- **Glass bottom batch bar:** Share · Vault · Favorite · Delete. Delete shows a confirm dialog and, on commit, a
  **Snackbar UNDO** for the batch. Vault batch respects `isSingleModeMovable` (P+L not movable) — non-movable
  selections disable the Vault action with a reason.

### 5.3 Per-item menu
Long-press (non-select) / overflow → glass context sheet: Play · Share · Favorite · Rename · Move-to-Vault ·
View settings · Delete. (No Export.)

### 5.4 Discovery (sort / filter / search)
- **Sort** (glass bottom sheet): Newest · Oldest · Longest duration · Largest size. Current sort announced via
  `stateDescription`.
- **Filter chips** under the title: All · ★ Favorites · mode (Portrait/Landscape/P+L) · (Vault is its own
  destination, not a main-grid chip).
- **Search** (inline field): on-device substring match over **derived/custom title + date**. No cloud, no ML.
- **Date fast-scroll scrubber** with a month/day bubble (load-bearing nav at 100+). Exposed as a `slider`/
  progress semantic with a scroll `accessibilityAction` and a landed-group announcement (a drag-only rail is
  invisible to AT).

### 5.5 Empty / loading
- **Loading:** placeholder grid cells (v1: simple static placeholder; shimmer is a v2 polish) — the two-stage
  emit + `hasLoaded` latch (which already prevents empty-flash) is kept.
- **Empty:** theme-native ring illustration + body + "Start recording" CTA, re-skinned to glass.

### 5.6 Pure-helper seams (JVM-testable; `isReturnDefaultValues=true`)
Per house convention, every framework-touching piece gets a pure-Kotlin sibling:
- `SmartTitle` — manifest → default label (`Sun · 14:32 · 8 clips · 12m`).
- `LibraryQuery` — pure sort comparators + filter-facet matching + search predicate over a row model.
- `SelectionReducer` — toggle / select-all-in-group / count / clear; immutable state.
- `StatusBadgePolicy` — manifest terminal+export state → `Recovered` / `Interrupted` / none.
- `ThumbnailCacheKey` — `(stableKey, sizeBytes, lastModified, durationMs)` → cache file path (stale-thumb guard).
- `ScrubberIndex` — scroll offset / item index → group label.
- `SegmentedTimelineSemantics` — cell state list → progressbar value + per-cell content descriptions.
- `StorageFormat` — per-row size + per-day totals formatting.
- Extend `HistoryRowFormatters` for the enriched headers.

---

## 6. Data model — sidecar metadata store (decision D)

> **codex-driven correction.** The original draft added `favorite`/`customTitle` to `SessionManifest` (schema
> v11→v12) with a "copy terminal fields verbatim" atomic writer. codex flagged this as **unsafe**: with
> whole-file atomic rename, a Library write that started from a *non-terminal* snapshot can lose a race to the
> service writing `COMPLETED` and **resurrect the stale terminal state** on rename — corrupting the recovery
> contract even though the writer never calls a terminal API. A static "no terminal API reference" gate cannot
> catch a stale full-file overwrite. **Resolution: keep UI metadata out of the manifest entirely.**

### 6.1 `LibraryMetadataStore` (sidecar)
A standalone store, independent of `SessionManifest`:
- One JSON file (`files/library_metadata.json`) mapping
  **`stableKey` → `{ favorite: Boolean, customTitle: String?, lastPlayedAt: Long? }`**.
  `stableKey` is the same row identity the History list already uses (abs path or content-URI) — so it covers
  **both** manifest-backed sessions **and** legacy `Rova_*.mp4` file-scan rows (a bonus: legacy rows become
  favoritable/renamable too, which the manifest approach could not do).
- Atomic temp-file-then-rename writes, **never touching any `SessionManifest`** → zero interaction with the
  terminal-write machinery, no schema bump, no migration, gates untouched.
- No cross-writer race: the service/recovery own manifests; the Library owns this sidecar; they share no file.
- Reads merge into the row model at load (and on edit) keyed by `stableKey`. Entries for deleted rows are pruned
  in the same cleanup pass as the thumbnail cache.
- **`lastPlayedAt` (owner #3) is reserved now, not surfaced in v1 UI.** It is written (best-effort, fire-and-forget)
  when a session opens in the player, so a future "Recently played" / "Continue" facet costs no second store
  change. Because it lives only in the sidecar, writing it never touches the manifest and shares the same atomic
  write. v1 sort/filter/search ignore it.

### 6.2 Why this is safe
- The dangerous coupling was "UI edits and terminal-state machinery write the same file." The sidecar removes the
  shared file, so last-writer-wins rename can never resurrect terminal state.
- No `SessionManifest` field, no schema version change, no legacy-tolerance concern.
- Pure `LibraryMetadataCodec` (serialize/parse/merge/prune) is JVM-tested. The real `org.json` is on
  `testImplementation`, so codec persistence is testable under JVM (same pattern as `SessionManifest` tests).

### 6.3 Strings
All new user-facing strings go in resources, **en + es** (`checkNoHardcodedUiStrings`, ADR-0022).

---

## 7. Performance at scale (100+ recordings)

The list already windows correctly (`LazyColumn`, keyed `items`); the grid (`LazyVerticalGrid`) will too. The
**one real gap** is the **in-memory-only** thumbnail cache (`ConcurrentHashMap`, dies with the ViewModel) →
every cold launch re-runs `MediaMetadataRetriever.getFrameAtTime()` per clip (~50–200 ms each).

**v1 fix — disk thumbnail cache:**
- Persist each extracted keyframe to `cacheDir/thumbnails/<hash>.webp`, downsampled to grid-cell size.
- **Cache key includes invalidators** (codex): `(stableKey, sizeBytes, lastModified, durationMs)` hashed — so a
  changed/replaced file at the same path/URI does not serve a stale thumbnail. For content-URIs where mtime is
  unavailable, fall back to `(stableKey, sizeBytes, durationMs)` best-effort.
- On load, read from disk before re-extracting; only extract on miss. Cold-load at 100+ goes ~3–5 s → ~500 ms.
- Eviction: simple size/LRU bound; entries for deleted/moved-out sessions cleaned alongside the existing key
  cleanup (and the sidecar prune).

**Explicitly NOT in v1:** Paging-3 (only needed past ~300–500 sessions), `MediaMetadataRetriever` pooling
(negligible), lazy-on-scroll thumbnails (worse UX than two-stage emit). All v2-or-never.

---

## 8. Accessibility (WCAG 2.2 AA — ADR-0020)

Always-in; the redesign closes deferred rows **21, 23, 32**.

- **Row 21 (player):** `SegmentedTimeline` gains `progressbar` role + per-cell content descriptions
  (Done / Current / Upcoming) via the pure `SegmentedTimelineSemantics` seam.
- **Rows 23/32 (History focus):** separate the warning-card Row click target from the dismiss `IconButton`
  (currently overlapping focus, `HistoryWarningStrip.kt:74-112`); verify focus order and focus visibility on the
  card/surface clickables; restore focus on return from the player (stash last-focused item key →
  `requestFocus()`), accounting for the known LaunchedEffect re-entry quirk.
- **Grid semantics:** `collectionInfo` on the grid + `collectionItemInfo` per tile; focus traversal lands on the
  cell; `isTraversalGroup` on the container; `traversalIndex` only where DFS order is wrong (batch bar ordering).
- **Tile label:** merged `contentDescription` = title + duration + status (`mergeDescendants`), so duration/
  status badges aren't separate focus stops.
- **Selection:** `toggleable` + `stateDescription`; polite live-region **count** node (anti-chant — announce on
  count change, not per tile); don't auto-move focus into the batch bar.
- **Reduced motion:** gate grid item-placement animations + any shared-element thumbnail→player transition behind
  the existing `ReducedMotion` seam (`checkA11yAnimationGated`).
- **Contrast over imagery (the AA risk):** a fixed semi-opaque **scrim** behind every caption/badge over a
  thumbnail or glass, guaranteeing ≥4.5:1 (≥3:1 large) at the worst pixel. This is structural (thumbnail bg is
  arbitrary, not a token) — verified by a `ContrastMath`-backed test against the scrim's worst-case composite,
  **not** by a token gate.
- **Targets:** all new clickables ≥24 dp (SC 2.5.8, `checkA11yTargetSizeToken`) with a declared role
  (`checkA11yClickableHasRole`).

---

## 9. Liquid Glass application (ADR-0028)

- **Glass is chrome, not content.** Translucency lives on the floating top bar, selection contextual bar, bottom
  batch bar, bottom nav, and sheets — via `GlassSurface(role = …)` with the appropriate `GlassRole`
  (`NavBar` / `BottomSheet` / `Banner` / `Card`). Recording cards sit on an **opaque** content plane.
- **No live blur on the scrolling grid.** Honors the `checkRecordSurfaceNoBlur` philosophy + GPU cost: pre-tinted
  scrims, not per-frame `RenderEffect`. The `GlassResolver` already falls back to an opaque (0.86α) surface at
  API <31 or under Reduce-Transparency.
- **Theme-native:** History is **not** a pinned-dark route — it adopts the active theme (verified across Aurora
  dark + Daylight light). Reduce-Transparency swaps glass for opaque automatically via the resolver.
- `checkGlassSurfaceRoleUsage` is honored (glass surfaces use roles).

---

## 10. ADR & gates

- **New ADR-0030 — "Library/History information architecture."** Records: the Export-cut rationale + file-missing
  handling (§3); the **sidecar `LibraryMetadataStore`** decision and the rule that **the Library never writes
  `SessionManifest`** (favorite/rename live in the sidecar — codex-driven, §6); the exceptional-only status-badge
  policy; vault-as-separate-destination; and the structural scrim-AA rule. Per house process, the ADR clause
  lands **before/with** the code.
- **New gate — `checkLibraryNoManifestWrite` (42nd, owner-approved).** Static-assert that Library/History code
  paths never call `SessionStore` manifest-write APIs — all Library metadata goes only through
  `LibraryMetadataStore`. Cleaner than guarding terminal-field access because the Library simply never touches
  manifests. Built in the **Foundation slice**, wired into `preBuild` (ADR-0030 clause → `check*` → `preBuild`).

No existing `check*` task is edited to pass; any new invariant follows ADR-clause → `check*` → `preBuild`.

---

## 11. Build slices (stacked PRs)

Each slice is an independently buildable, JVM-tested, device-smoked, mergeable PR (stacked per
`memory/feedback_stacked_pr_merge_train.md`). Order:

**Each slice ships its own surface's accessibility** (semantics, roles, labels, target sizes, live-regions,
reduced-motion) — a11y is built **with** each interactive surface, not retrofitted (codex). PR5 is the
**closeout/audit** for the cross-cutting deferred rows, not first-implementation of semantics.

1. **Foundation — data + sidecar + cache + ADR.** ADR-0030; `LibraryMetadataStore` (`favorite`, `customTitle`,
   reserved `lastPlayedAt`) + pure `LibraryMetadataCodec` + the **42nd gate `checkLibraryNoManifestWrite`**;
   `SmartTitle`; disk thumbnail cache (invalidator key); pure
   `LibraryQuery` / `StatusBadgePolicy` / `StorageFormat`. No visible UI change beyond wiring. (Tests: all new
   pure helpers + codec persistence.)
2. **Layout — Hero + Grid + List toggle + glass re-skin.** New grid/hero composables on the opaque content plane;
   glass chrome (top bar, bottom nav); enriched date headers (size totals); empty/loading re-skin; cards show
   duration + exceptional badge + P+L + scrim. **+ its a11y:** grid `collectionInfo`/`collectionItemInfo`, merged
   tile `contentDescription`, scrim-contrast test, reduced-motion gating on item placement.
3. **Selection + batch actions.** Long-press selection, glass contextual top bar (live count), bottom batch bar
   (Share/Vault/Favorite/Delete), per-day select-all, Snackbar UNDO, vault `isSingleModeMovable` gating.
   **+ its a11y:** `toggleable`+`stateDescription` tiles, polite live-region count, no focus-steal into the bar.
   `SelectionReducer` tests.
4. **Discovery — sort + filter + search + scrubber.** Sort sheet, filter chips, inline search, date fast-scroll
   scrubber. **+ its a11y:** sort sheet modal/`stateDescription`, scrubber `slider` semantic + landed-group
   announce. `ScrubberIndex` + `LibraryQuery` search/filter tests.
5. **Accessibility close-out / audit.** Cross-cutting deferred rows: rows 23/32 (History warning-card focus
   separation, focus order/restore on return from player); **row 21** `SegmentedTimeline` `progressbar` role +
   per-cell labels (`SegmentedTimelineSemantics`); full-surface TalkBack pass.

(Favorite/Rename UI affordances ride slices 2–3 on top of slice 1's sidecar.)

---

## 12. Out of scope

- **v2 (strong candidates):** Recently-Deleted/undo-trash bin; full storage-management "free up space" screen;
  batch multi-share/export; select-all-matching-filter; "re-record with these settings" (clone config bundle);
  shimmer skeletons; Paging-3; pin-to-top distinct from favorite.
- **Not worth it:** cloud sync/export; user-created folder trees; free-form tags; per-segment rename; semantic/AI
  search; `MediaMetadataRetriever` pooling.

---

## 13. Risks & open items

- **Metadata-write safety** (was the highest risk) is **designed out** by the sidecar (§6): the Library never
  writes `SessionManifest`, so the terminal-state race is impossible. Residual: sidecar/manifest can drift if a
  session id/path is reused — mitigated because `stableKey` is path/URI-unique and the prune pass drops orphans.
  Device smoke still confirms a favorite/rename never perturbs recovery classification.
- **Scrim AA over arbitrary frames** can't be tool-verified per pixel — enforced by worst-case composite test +
  a fixed scrim floor, not a token gate.
- **Disk cache invalidation** on session delete/move-out / file change must not orphan or mis-serve thumbnails —
  covered by the invalidator-enriched key + the key-cleanup pass extended to disk.
- **Scope size** — mitigated by the 5-slice stacked plan; each slice smoke-tested on RZCYA1VBQ2H before stacking
  the next.
```

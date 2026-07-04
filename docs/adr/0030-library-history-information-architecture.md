# ADR-0030: Library/History information architecture

Status: Accepted (2026-06-14)
Amended 2026-06-14 (Slice 3): recovery-keep terminal write relocated to `ui/recovery/RecoveryViewModelFactory.kt`; `checkLibraryNoManifestWrite` exception machinery removed — gate is now exception-free (Option A, owner + codex).

## Context
The Library/History surface (browse all recordings) is being redesigned to the Liquid Glass language. The
redesign adds favorite, rename, sort/filter/search, multi-select batch actions, and a disk thumbnail cache.
Two design decisions need a recorded invariant.

## Decision

1. **Export is not a Library action.** Every FINALIZED session already lives in user-visible storage at record
   time (Tier1 MediaStore, Tier2/3 `Movies/Rova`, or the SAF folder). `Share` hands a content-URI to another app
   (it does not save a copy). `ExportPipeline.export()` is callable only from `RovaRecordingService.performMerge`
   and the cold-launch recovery runner — not from UI. A Library "Export"/"Save" button would solve no real user
   problem. If a session's public artifact later goes missing, the UX is "file missing → remove from Library",
   not re-export. Vault sessions leave via the existing move-out.

2. **Library UI metadata lives in a sidecar, never in `SessionManifest`.** `favorite`, `customTitle`, and a
   reserved `lastPlayedAt` are stored in `LibraryMetadataStore` (a JSON file keyed by row `stableKey`),
   completely separate from the manifest. Rationale: with whole-file atomic-rename manifest writes, a UI write
   that started from a non-terminal snapshot can lose a race to the service writing `COMPLETED` and resurrect
   stale terminal state. Keeping UI metadata out of the manifest removes the shared file, so the race is
   impossible. Invariant: **Library/History code never calls a `SessionManifest`-mutating `SessionStore` API**
   (`markTerminated`, `appendSegment`, `submitPersistFinalizedSegment`, `setExport*`, `setVault*`,
   `setStopRequested`, `setPendingMoveOut*`). Enforced by `checkLibraryNoManifestWrite`.

   **Recovery-keep terminal write lives in the recovery subsystem (amended Slice 3).** The recovery-keep
   action (`markTerminated(MULTI_SEGMENT_KEPT)`, wired through `RecoveryViewModel.markKeptRaw`) is constructed
   in `ui/recovery/RecoveryViewModelFactory.kt` — its principled home (recovery subsystem, ADR-0005), outside
   the `ui/library/` + History/Library-screen scope that `checkLibraryNoManifestWrite` guards. No in-scope
   exception marker is needed; the gate now asserts **zero** manifest-mutating `SessionStore` calls in
   Library/History UI code, full stop. Only recovery-owned terminal-repair writes are permitted outside this
   gate — favorite, rename (`customTitle`), and `lastPlayedAt` go **only** through `LibraryMetadataStore`
   regardless of which file calls them. `ui/recovery/` is not a general manifest-write location.

   (History: before Slice 3 this write was co-located in `HistoryScreen.kt` behind an `ADR-0030-allow:
   recovery-keep-raw` marker that the gate honoured only in that file. The redesign retired `HistoryScreen.kt`
   and moved the recovery factory to its proper subsystem, letting the gate drop the marker loophole.)

3. **Status badges are exceptional-only.** Cards show no "Complete" badge. `RECOVERED` is surfaced for
   `MULTI_SEGMENT_KEPT`; `INTERRUPTED` for `KILLED_BY_SYSTEM` / `KILLED_FORCE_STOP` or `exportState == FAILED`.
   All other states (including `COMPLETED`, clean `USER_STOPPED`) show no badge.

4. **Vault stays a separate auth-gated destination.** Vaulted sessions never appear in the main grid; the
   redesign only re-skins the existing vault entry to glass.

5. **Captions/badges over thumbnails carry a structural scrim** guaranteeing ≥4.5:1 (≥3:1 large) at the worst
   pixel — not a token gate (the background is an arbitrary video frame).

## Amendment (2026-06-24): safety-stop badge

A third exceptional badge `LibraryBadge.AUTO_STOPPED` (safety stops: `THERMAL` / `LOW_STORAGE`, both persisted as `USER_STOPPED` with a distinct `StopReason`) joins `RECOVERED` / `INTERRUPTED`. It reuses the locked `IconStatus.Interrupted` color (no new locked color — `checkStatusColorLocked` clean) with a reason-aware glyph (thermometer for thermal, storage for low-storage). Scheduled-window stops (`SCHEDULE_WINDOW`) remain badge-less — a planned end is not "exceptional" (decision 3 logic extended). This refines decision 3's badge set; the taxonomy is centralized in `data/StopCategory.kt` and shared with the recovery card. Display-only — no schema change. See ADR-0016, ADR-0027, and `docs/superpowers/specs/2026-06-24-safety-stop-distinction-design.md`.

## Consequences
- No manifest schema change; no migration. Legacy file-scan rows (no manifest) gain favorite/rename for free
  (the sidecar keys on `stableKey`, which exists for every row).
- A new static gate (`checkLibraryNoManifestWrite`, the 42nd) enforces decision 2.

## Amendment (2026-07-02) — Session-list presentation (Direction A)

Spec: docs/superpowers/specs/2026-07-02-library-session-list-design.md. Owner-approved via brainstorming + codex peer review.

1. **Single list presentation.** The grid view, the GRID/LIST view-mode toggle, and the `libraryViewMode` preference are removed. The Library renders one `LazyColumn` of session rows grouped by day.
2. **Hero replaced by in-timeline latest-row accent.** The standalone "Latest Recording" hero card is removed. When sort = Newest, the first visible row renders as a restrained accent variant (tinted container, larger thumbnail, "Latest" eyebrow, explicit Play/Resume pill). Same information anatomy as every row; NO media autoplay anywhere in the Library (trust: background-recorded video must not auto-preview).
3. **DualShot = one row per session.** The two per-side rows collapse into one session row (session-canonical `stableKey` = `RecordingIdentity.sessionKey`), showing a DualShot badge and two explicit, individually-labeled ≥48dp side actions ("Portrait · m:ss" / "Landscape · m:ss"). Row tap plays the Portrait side; the side actions make that default visible. Batch share/delete/favorite operate per-session (both sides). Vault: DualShot sessions remain NOT vault-movable (ADR-0025 status quo).
4. **Sticky compact day headers** with relative + absolute labels (Today / Yesterday / weekday within 7 days / date), heading semantics, per-day count + size summary.
5. **Density setting.** `libraryDensity` preference (COMFORTABLE default | COMPACT) controls row/thumbnail dimensions; replaces the retired view-mode toggle slot.

§2 (sidecar-only metadata, `checkLibraryNoManifestWrite`) is unchanged and continues to bind the new presentation.

## Amendment (2026-07-04) — Thumbnail-first presentation: Adaptive Bento Timeline

**Canonical visual spec: `docs/design/library-bento.html` (v3.2, FROZEN 2026-07-04).** Owner-selected from three
codex-reviewed interactive concepts, then refined across three codex-reconciled review rounds (token/tap-model
pass, usability pass, header de-chroming). Per the HTML-first workflow (CLAUDE.md "Design workflow"): Compose
*transcribes* the frozen spec; ambiguities discovered during implementation route back to the HTML for
re-approval; pixel-match review against the spec gates the merge. Exact geometry, palette-derivation rules,
type scale, and motion tokens live in the spec and the implementation spec — this amendment locks the
*behavioral* invariants.

This amendment **supersedes the presentation layer of the 2026-07-02 amendment**: its §1 (single row list),
§2 (latest-row accent variant + Play/Resume pill), and §5 (`libraryDensity` preference) are retired. Its §3
(session-canonical identity, one entry per DualShot session, per-side ≥48dp play targets) and §4 (sticky day
headers with relative/absolute labels) carry forward in the new form below. Decisions 1–5 of the base ADR and
the 2026-06-24 safety-stop amendment continue to bind.

1. **Presentation.** The Library renders one vertical scroll of day sections; each day lays its sessions out
   as a 6-column bento grid using pre-authored span patterns. Density is **recency-graduated by day age**
   (featured ≤1 day / standard 2–6 / archive ≥7) — this replaces the manual `libraryDensity` preference and
   its top-bar toggle, which are removed (with pref-key cleanup). Chronological order is never re-sorted; row
   patterns are chosen around content (a DualShot session always lands on a span ≥3 slot) and rotate on the
   day's age so filter/search changes never reshuffle a day's pattern. There is no sort control (chronology +
   the date rail are the navigation model).

2. **Tap plays.** Tapping a tile opens the Player directly (restores the shipped row-tap-plays behavior).
   A DualShot tile is a diptych: two panes, each its own accessible tap target — tap a side, play that side;
   **Portrait is always the left pane, everywhere**. The details surface moves to selection mode: an info
   action enabled when exactly one session is selected. Rename, favorite-from-details, vault-move, and delete
   live there. Export remains absent (decision 1).

3. **Selection.** Long-press enters selection (plus an explicit top-bar entry); day headers gain per-day
   select-all circles; batch favorite / vault / delete (with Undo toast) operate on the selection. The
   selection can never silently hold sessions the current view hides — filter/search changes prune it to the
   visible set, and per-item mutations drop the affected id.

4. **Day headers are ground, not chrome.** Headers are plain labels in the timeline (no bar, no blur, no
   border, identical anatomy in both states). While pinned, the page's own background color washes over the
   exit edge (solid hold behind the label, long dissolve tail, no bottom edge); the pinned state must be
   applied synchronously with content changes so a pinned header never paints unveiled over thumbnails.
   Epoch-keyed day identity and midnight re-stamp behavior from PR-C carry forward.

5. **Orientation is legible.** Every tile's meta pill leads with the recording's shape: singles show their
   orientation glyph (portrait ▯ / landscape ▭); DualShot shows the ▯▭ pair in pane order. The details facts
   line names it in words; single-tile accessibility labels include it. (Media-relative treatment per
   decision 5's structural-scrim rule.)

6. **Latest = chip, not variant.** The newest recording (unfiltered view only) carries a small accent chip;
   there is no enlarged/accented row variant and no Resume pill (the shipped Player owns resume, #137).
   **No media autoplay anywhere in the Library** — unchanged trust rule; the Player opens paused.

7. **Chrome.** Filter chips (All / Favorites / DualShot) and the stats line scroll away with content — day
   headers own the pinned top. Search matches names and date words and reports "X of Y recordings". The date
   scrub rail (auto-hiding right-edge thumb + date bubble, slider semantics, exact-day keyboard stepping)
   replaces the PR-C scrubber anatomy. The Private Vault remains a quiet destination row above the timeline
   (decision 4 binds: never a filter).

8. **Exceptional status badges carry forward** (decision 3 set + AUTO_STOPPED): rendered on the tile with the
   structural media scrim of decision 5, taking precedence over the latest chip's slot when both apply. The
   frozen spec models the treatment; the badge taxonomy and colors are unchanged (`data/StopCategory.kt`,
   locked status colors).

§2 (sidecar-only metadata, `checkLibraryNoManifestWrite`) is unchanged and continues to bind: favorite,
rename, and playback-position metadata go only through their existing stores; the new presentation adds no
manifest writes.

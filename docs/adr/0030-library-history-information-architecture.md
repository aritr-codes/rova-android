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

# ADR-0030: Library/History information architecture

Status: Accepted (2026-06-14)

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

3. **Status badges are exceptional-only.** Cards show no "Complete" badge. `RECOVERED` is surfaced for
   `MULTI_SEGMENT_KEPT`; `INTERRUPTED` for `KILLED_BY_SYSTEM` / `KILLED_FORCE_STOP` or `exportState == FAILED`.
   All other states (including `COMPLETED`, clean `USER_STOPPED`) show no badge.

4. **Vault stays a separate auth-gated destination.** Vaulted sessions never appear in the main grid; the
   redesign only re-skins the existing vault entry to glass.

5. **Captions/badges over thumbnails carry a structural scrim** guaranteeing ≥4.5:1 (≥3:1 large) at the worst
   pixel — not a token gate (the background is an arbitrary video frame).

## Consequences
- No manifest schema change; no migration. Legacy file-scan rows (no manifest) gain favorite/rename for free
  (the sidecar keys on `stableKey`, which exists for every row).
- A new static gate (`checkLibraryNoManifestWrite`, the 42nd) enforces decision 2.

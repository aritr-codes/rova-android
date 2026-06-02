# ADR-0024 — Custom export destination via Storage Access Framework (Track-B B4b)

**Status:** Accepted

**Date:** 2026-06-02

**Deciders:** Rova owner

**Supersedes / amends:** none. Extends the tiered public-export model (ADR-0003) with a fourth, user-chosen destination route. The existing Tier 1/2/3 rails are untouched; SAF is an additive route selected only when the user has picked a custom folder.

## Context

Rova exports a finished recording to the public **Movies/** collection by a tier chosen at session start from the device API level: `Tier1Exporter` (29+ MediaStore), `Tier2Exporter` (26–28 scoped temp + `MediaScannerConnection`), `Tier3Exporter` (24–25 direct path). Track-B B4 asks for a **custom save location** — a user-chosen folder, including an SD card — so exports land there instead of Movies.

The Android-sanctioned way to write outside the app sandbox to an arbitrary, possibly-removable tree is the **Storage Access Framework** (`ACTION_OPEN_DOCUMENT_TREE` → persisted URI grant → `DocumentFile`). SAF descriptors are **not seekable**, so a `MediaMuxer` can never target one. This is the central constraint the design is built around.

## Decision

### Tier ⇒ route reframe — `ExportTier.SAF_DESTINATION`

The four-way `ExportTier` enum gains a `SAF_DESTINATION` value. Tier was an *API-level* selector; it now also encodes a *destination* choice. `SessionManifest.currentExportTier(hasUsableSafFolder)` returns `SAF_DESTINATION` when a usable folder is frozen for the session, else falls through to the API-level tier. The choice is **frozen at session start** into the manifest (like every other tier decision) so a mid-session settings change cannot retarget an in-flight recording.

### SAF = pre-Q mechanism + SAF publish (muxer never on SAF)

`SAF_DESTINATION` rides the **pre-Q private-temp rails**: mux to a local private temp MP4, validate it, then **PUBLISH** by copying the bytes into the user-chosen SAF document with a sequential `openOutputStream("wt")` copy (no seeking — works on every provider, incl. cloud/removable), validate the doc, then finalize (clearing `privateTempPath`). The muxer only ever writes the local temp; SAF is a byte-copy sink, never a mux target.

`SafExporter` is the pure core (every Android op injected as a lambda); `SafAndroidOps` is the thin `DocumentFile` seam. The seam uses `DocumentFile`, not a raw `ContentResolver.query`, so it sits outside the MediaStore-only `checkExportPendingVisibilityOnQuery` discipline — it talks to a `DocumentsProvider`, not MediaStore.

### Commit-before-stream invariant + `checkSafTargetCommittedBeforeStream` (28th gate)

The target doc URI MUST be committed to the manifest (`setExportSafTarget`) **before the first byte is written to it**. Otherwise a crash mid-copy leaves an orphan SAF document the next scan cannot find or reclaim. The 28th `check*` gate (wired into `preBuild`) is a line-oriented tripwire over `service/export/*.kt`: any `copyFileToDocument(` / `openOutputStream(` must be preceded **in the same file** by a `setExportSafTarget` / `setSafTarget(` commit. `SafAndroidOps.kt` — the raw-stream seam — is exempt. Consistent with the existing gates: a structural tripwire, not a correctness proof.

### Validate-before-delete recovery

On cold-launch recovery, a committed `safTargetDocUri` that **already validates** (exists, non-zero length) is the GOOD artifact — a finalize write was lost, not the bytes. It is finalized, never re-copied or deleted. Only when the committed doc is absent/invalid does recovery **re-mux from the authoritative on-disk segments** (not a possibly-partial temp) and re-publish. The re-mux is the lone SAF-recovery `VideoMerger.mergeSegments` call and lives in `SafRecoverBuilder` under `service/export/`, keeping `checkExportPipelineSingleEntry` green and `RovaApp` free of any `VideoMerger` reference. Crash-safety rides the existing `privateTempPath` retention gate: segments cannot be cleaned up until the SAF artifact validates.

### Failure classification — permanent vs transient (retry budget 3)

- **PERMANENT** — `isPermissionHeld()` is false (the user revoked the folder grant) **OR** the transient-retry budget (3) is exhausted → `ExportResult.SafFolderUnavailable` + `setExportFailed`, surfacing `WarningId.SAVE_FOLDER_UNAVAILABLE` (a flat, non-gating ADVISORY appended last in the precedence order). `RovaSettings.saveFolderUnavailable` flips true so the warning shows until the user repicks.
- **TRANSIENT** — a throw while permission is still held and budget remains → `MuxFailed` / `CopyFailed`, the manifest left in its in-progress state (MUXING/COPYING — **no terminal write**), `incrementSafTransientRetry`, retried next cold launch. Recovery skips `FAILED` sessions, so a transient failure must never `setFailed`.

Adding the enum value and the sealed `ExportResult.SafFolderUnavailable` variant deliberately breaks every `when` over `ExportTier` / `ExportResult` until each compiler-surfaced site handles the SAF case — Kotlin exhaustiveness is the audit.

### P+L (DualShot) SAF deferred

The per-side data layer exists (`portraitSafTargetDocUri` / `landscapeSafTargetDocUri` fields, `*ForSide` setters, side-aware `SafExporter` / `SafRecoverBuilder`), but **session-start freeze is gated to single-mode this slice**: `hasUsableSafFolder()` returns false for `mode == "PortraitLandscape"`, so a P+L session always uses the default location. Per-side reveal is a clean follow-up — the plumbing is present, only the freeze gate withholds it.

## Non-goals (B4b)

No P+L-SAF reveal; no in-place playback from the SAF doc (History/Player keep using the content-URI path, as Tier 1 does); no migration of already-exported files when the folder changes; no multi-folder / per-mode destinations. Each is a later slice if asked.

## Consequences

- A user-chosen folder (incl. SD card) becomes the export destination with no change to the API-level tier rails — purely additive.
- Crash-safety is unchanged in spirit: the `privateTempPath` retention gate already protects segments; SAF just adds a validate-before-delete publish step on top.
- Cost: a SAF export does a full extra byte-copy (temp → SAF doc) the in-place Movies tiers avoid — acceptable for an opt-in custom destination.
- The 28th gate locks the commit-before-stream ordering shut for any future SAF stream op.

## Hard invariants preserved

- No existing `check*` gate edited; this adds the 28th (`checkSafTargetCommittedBeforeStream`). `checkExportPipelineSingleEntry`, `checkExportCleanupPredicate`, and the pending-row visibility gates stay green — SAF reuses `privateTempPath` retention (no cleanup-predicate edit), keeps the single `ExportPipeline.export(` entry site, and never calls `resolver.query` / `copyToPublicMovies`.
- The muxer never targets a SAF descriptor (always a local temp).
- House conventions: pure-helper extraction (`SafExporter`), thin framework seam (`SafAndroidOps`), ADR-clause → `check*` → `preBuild`.

## Implementation reference

`docs/superpowers/specs/2026-06-02-b4-saf-export-destination-design.md` and `docs/superpowers/plans/2026-06-02-b4-saf-export-destination.md`.

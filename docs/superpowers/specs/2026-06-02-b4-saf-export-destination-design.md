# B4b — User-selectable export destination (custom SAF folder) — Design

**Date:** 2026-06-02
**Status:** Approved (brainstorming)
**Owner:** Rova owner
**Track:** Settings expansion Track B, slice B4b (B4 decomposed into B4a working-storage-on-SD *(deferred)* + B4b export-destination *(this slice)*).
**Peer review:** codex (gpt-5.x) consulted on the architecture; its lost-recording critique is folded in (private-temp intermediary, validate-before-delete recovery, failure classification, createDocument auto-rename capture).

## Goal

Let the user choose a custom folder (via the Storage Access Framework) as the **save location** for finished exports. When a folder is set, the merged video is written **there instead of** MediaStore/Movies (replace, not a second copy). The folder may live on an SD card, USB-OTG, or any `DocumentsProvider` the user navigates to. When unset, the existing tiered export path is **100% unchanged**.

## Decisions (from brainstorming)

1. **Scope = export destination only** (B4b). Working/segment storage stays internal; moving *segment* capture to SD is B4a, deferred.
2. **Custom folder, not "SD card"** (`ACTION_OPEN_DOCUMENT_TREE`). Android gives no robust SD-only API; SAF is location-agnostic and uniform on API 21+. The setting is honestly presented as a folder picker; the user navigates to their SD card if they want it.
3. **Replace, not add.** A set folder redirects the export; the default MediaStore/Movies write is skipped. Default path untouched when unset.
4. **Architecture = SAF as a frozen export route (refined Approach A).** SAF is modeled as a destination **variant of the preQ (Tier 2/3) path**: mux to a local private temp, then publish by copying bytes into the SAF document. It is frozen per session into `manifest.exportTier` and dispatched on recovery by `manifest.exportTier` — riding the existing "frozen tier travels with the manifest" rails. The MediaMuxer **never** writes to a SAF descriptor.

### Why the muxer never touches SAF (codex critique f, adopted)

A direct `MediaMuxer(saf-fileDescriptor)` makes the destination provider part of the critical mux path. SAF `openFileDescriptor` seekability is **not portable** (provider-specific; cloud providers may not support seekable `rw`), and MediaMuxer requires a seekable FD. Routing through a local private temp:

- removes the seekability risk entirely (mux always targets a local file),
- works on **every** provider including cloud (the publish step is a sequential `openOutputStream` byte copy — no seeking),
- reuses the **existing `privateTempPath` retention rails**, so SAF crash-safety comes for free with no cleanup-predicate change.

## Scope

### In

- `RovaSettings.saveLocationTreeUri: String?` + `saveLocationLabel: String?` (both backed-up; null = internal default).
- Settings UI: a "Save location" row (Internal default / folder label), folder picker launch, "Use internal storage" clear action, pick-time write probe + persisted-permission grant.
- `ExportTier.SAF_DESTINATION` enum value; `currentExportTier()` made save-location-aware (route selection at session start).
- `SafExporter` (under `service/export/`): preQ-style mux→temp→validate→copy-to-SAF→validate→finalize; returns `ExportResult`.
- New manifest fields: `safTargetDocUri: String?` (+ `portraitSafTargetDocUri`, `landscapeSafTargetDocUri` for P+L). Manifest schema bump.
- `SessionStore.setExportSafTarget(...)` setter (commit point before the SAF doc is written).
- Recovery: SAF dispatch in `RovaApp`'s `recoverSession` / `validateTierArtifact` lambdas (validate-before-delete).
- Failure classification (permanent vs transient) + a `WarningId` surfacing a permanently-unavailable save folder.
- New `check*` gate: `checkSafTargetCommittedBeforeStream`.
- New **ADR-0024** amending ADR-0003.
- Pure-JVM tests for all of the above.

### Out (deferred)

- B4a: recording **segments** to SD (`getExternalFilesDirs`) — separate slice.
- Auto-fallback to internal on permanent SAF failure (this slice **fails + warns**; a later slice may add silent fallback).
- `renameDocument`-based atomic publish (accept a brief visible partial; safety comes from never clearing `privateTempPath` until the SAF artifact validates).
- Migrating already-exported history to a new folder; multi-folder profiles.

## Mechanism

### Route selection (frozen per session)

`currentExportTier()` today is a pure function of `Build.VERSION.SDK_INT`. It gains one input — whether a usable SAF folder is configured:

```
fun currentExportTier(hasUsableSafFolder: Boolean): ExportTier =
    if (hasUsableSafFolder) ExportTier.SAF_DESTINATION
    else <existing SDK_INT branch>
```

`hasUsableSafFolder` = `saveLocationTreeUri != null` AND the tree appears in `contentResolver.persistedUriPermissions` with write access. Resolved at **session start**, frozen into the manifest exactly as the SDK tier is today. `ExportPipeline.export()` is changed to honor the **frozen** tier (passed in / read from the session) rather than recomputing from SDK at export time — so a mid-session setting change cannot split live export from recovery (recovery always uses `manifest.exportTier`).

Adding `SAF_DESTINATION` makes every non-exhaustive `when (exportTier)` a **compile error** until handled — Kotlin exhaustiveness is the audit codex asked for (every dispatch, validator, log label, check). Each is reviewed as it surfaces.

The enum **name** `ExportTier` is kept (renaming to `ExportRoute` would churn `checkExportTierReadTolerant` and every `getString("exportTier")` site for no functional gain); KDoc + ADR-0024 state that "tier" now means "export route," where Tier 1/2/3 are SDK-derived routes and `SAF_DESTINATION` is a setting-derived, API-orthogonal route.

### Export (SafExporter, happy path)

1. `VideoMerger.mergeSegments(segments, privateTempFile, onProgress)` — mux to a local private temp (the existing preQ collision-probe allocates the temp path).
2. Validate the temp MP4 (`TierArtifactValidator` — exists, non-zero, parses).
3. `SessionStore.setExportPrivateTarget(privateTempFile)` — manifest commit (preQ rail).
4. `DocumentsContract.createDocument(resolver, treeDocUri, "video/mp4", displayName)` → child doc Uri (or `null`/throw → `PendingInsertFailed`-analog failure). Capture the **actual** `COLUMN_DISPLAY_NAME` (provider may auto-rename) and persist it for logs/label.
5. `SessionStore.setExportSafTarget(docUri)` — manifest commit **before** any byte is written to the doc (gate `checkSafTargetCommittedBeforeStream`).
6. `resolver.openOutputStream(docUri)` → sequential copy from the private temp (no seeking; works on all providers).
7. Validate the written SAF doc (length>0, parses).
8. `SessionStore.setExportFinalized(clearPrivateTempPath=true)` — clears `privateTempPath`; session now cleanup-eligible.

On any failure between steps 1–7: classify (below), delete the SAF doc **only if** this run created it and it has not validated, leave `privateTempPath` populated (segments + temp retained), no terminal write — cold-launch recovery reconciles.

### Recovery (validate-before-delete — codex critique c)

Dispatched by `manifest.exportTier == SAF_DESTINATION`. For a session in `MUXING`/`COPYING` (or `FINALIZED && privateTempPath != null`):

1. If `safTargetDocUri != null` and the doc **exists, length>0, parses** → the mux+copy actually completed and only the finalize write was lost → `setExportFinalized(clearPrivateTempPath=true)`. **Never delete a validated doc.**
2. Else if the private temp exists & validates → (re)create the SAF doc if needed and re-copy temp→doc, then validate + finalize.
3. Else (temp gone too) → re-mux from retained segments into a fresh temp, then publish.
4. If the persisted permission is gone → see classification.

`validateTierArtifact(SAF manifest)` = `safTargetDocUri` doc exists, length>0, parses — used by the existing late-terminal pass to write `markTerminated(COMPLETED)`.

`needsExportRecovery` is **unchanged**: SAF uses `privateTempPath` exactly like Tier 2/3, so `FINALIZED && privateTempPath == null` is "fully done," and `MUXING/COPYING` or `FINALIZED && privateTempPath != null` re-drives. The cleanup predicate is **unchanged** — `privateTempPath != null` already blocks deletion until the SAF artifact is confirmed.

### Failure classification (codex critique d)

| Class | Trigger | Action |
|-------|---------|--------|
| **Transient** | `openOutputStream`/`createDocument` throws `FileNotFoundException`/`IOException` while the tree is **still granted** (SD remounting, provider busy) | retain temp+segments, no terminal write, retry next cold launch. Bounded retry counter in the manifest; after N consecutive transient failures → escalate to permanent. |
| **Permanent** | tree absent from `persistedUriPermissions` (user revoked / cleared) or the tree document no longer resolves | `setExportFailed`, surface `WarningId.SAVE_FOLDER_UNAVAILABLE` (idle banner: "Save folder unavailable — pick a new folder or switch to internal"). The recording is **not lost** — segments/temp retained; user can re-pick and the next export attempt resumes. |

The bounded retry counter closes codex's "silent infinite loop" concern.

### createDocument collision / auto-rename (codex critique e)

`createDocument` may return a doc whose display name differs from the request (provider-side dedup) or collide. Mitigation: pre-query the tree's children for the intended name and apply the preQ-style `_2/_3/…` suffix before create; always treat the **returned** `COLUMN_DISPLAY_NAME` as authoritative and persist it.

## ADR-0024 (new)

"User-selectable export destination (SAF route)." Amends ADR-0003 §tiered-export:

- **Tier ⇒ export route.** `ExportTier` enumerates routes: Tier 1/2/3 are SDK-derived (MediaStore / public-Movies), `SAF_DESTINATION` is setting-derived and API-orthogonal. Route is frozen per session and travels with the manifest; recovery dispatches by `manifest.exportTier`, never the running SDK.
- **SAF route = preQ mechanism + SAF publish.** Mux to private temp, validate, copy to the SAF document, validate, then clear `privateTempPath`. The muxer never targets a SAF descriptor (seekability is not portable).
- **Commit-before-stream invariant.** `setExportSafTarget(docUri)` must precede the first write to the doc, mirroring Tier 1's `setExportPending`-before-FD. Enforced by `checkSafTargetCommittedBeforeStream`.
- **Validate-before-delete invariant.** Recovery must validate an existing `safTargetDocUri` before deleting/recreating — a lost finalize write must not destroy a good artifact.
- **Failure classification.** Permanent (revoked/missing tree) surfaces a warning + `FAILED`; transient retries with a bounded counter.

## check gate

`checkSafTargetCommittedBeforeStream` (wired into `preBuild`, the 28th gate): in `service/export/`, any `openOutputStream(`/write against the SAF target must be preceded in source by a `setExportSafTarget(` reference — a line-oriented tripwire consistent with the existing export gates (not a correctness proof). Cites the ADR-0024 commit-before-stream clause on failure.

## Existing-gate compliance

- `checkExportPipelineSingleEntry` — satisfied: `SafExporter` lives under `service/export/`; `ExportPipeline.export(` stays single-site in `RovaRecordingService`; `VideoMerger` mux callers stay under `service/export/`.
- `checkExportCleanupPredicate` — unchanged source (SAF reuses `privateTempPath`; all four gate tokens stay present).
- `checkExportNoCopyToPublicMovies` — the SAF byte copy must **not** reintroduce a `copyToPublicMovies` symbol (it is a distinct `SafExporter` path).
- `checkExportTierReadTolerant` — preserved (`exportTier` read stays opt + `currentExportTier()` fallback; SAF value tolerated).
- `checkCompletedWriteOnlyFromPerformMerge` — preserved (COMPLETED still only from `performMerge` / late-terminal pass).

## Test plan (pure-JVM, `isReturnDefaultValues = true`)

- `SafExporter` with injected `createDocument` / `openOutputStream` / `validate` / `deleteDoc` lambdas (no Android types): happy path; mux-fail; createDocument null/throw; copy-fail; SAF-validate-fail; auto-rename capture; permanent vs transient classification; bounded-retry escalation; P+L per-side targets.
- Recovery validate-before-delete truth table (validated doc → finalize-no-delete; invalid doc + valid temp → re-copy; both gone → re-mux).
- `currentExportTier(hasUsableSafFolder)` selection matrix.
- `RovaSettings` save-location round-trip (set/clear/label).
- `checkSafTargetCommittedBeforeStream` gate self-test (a synthetic violation fails).
- Full suite baseline +N, 0-0-0; `lintDebug` green incl. all 28 checks; `assembleDebug` green.
- **Device (manual, owner):** pick an SD folder; record→stop→auto-export lands in the chosen folder, visible in a file manager; revoke permission → next export warns, recording retained; re-pick → resumes. P+L produces two per-side files in the folder.

## House-convention compliance

- Pure-helper / thin-seam: `SafExporter` is injectable-lambda testable; Android calls isolated to a thin ops seam like `Tier1AndroidOps`.
- ADR-clause-first: ADR-0024 + the new gate land with (not after) the source change.
- No existing `check*` gate edited; this adds the 28th.
- JVM-only tests; no Robolectric/instrumented.
- Reliability ethos preserved: no data-loss window — every failure path retains segments/temp until the SAF artifact validates.

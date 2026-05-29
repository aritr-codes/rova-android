> **Note:** v5 is preserved in-tree as the content base for `ROADMAP_v6.md`'s surgical-diff structure. **Do not edit v5 — see v6 for the active diff.** A future PR may promote v6 to self-contained and archive v5; until then, v5 is read-only.

---

# Rova — Production Readiness Roadmap (v5)

> Surgical revision of `ROADMAP_v4.md`. The storage/export architecture in §1.6 / §1.7 / risk C7 / risk C16 / §"Out of Scope" / §"Lint Rules" is rewritten to correctly handle API-level differences in `MediaStore` (specifically: `IS_PENDING` is API 29+ only). All other sections — scheduling (§3), service model (§3.1), STOP path (§1.3), surface lifecycle (§1.4 / C13), force-kill detection (§4), drift policy (§3.4), wakelock discipline (§1.8), C1 / C3 / C8 / C9 / C11 / C12 / C14 / C15 / C18 / C19 — are **unchanged from v4** and remain authoritative as written there.

---

## What Changed from v4

| # | v4 problem | v5 fix |
|---|---|---|
| 1 | `IS_PENDING` was used in v4's API 24–25 export sequence and implied for API 26–28. `MediaStore.MediaColumns.IS_PENDING` is **API 29+** only. The roadmap split export by *muxer* API level, not by *storage* API level. | Export is split by **storage API level (`MediaStore` semantics)**, not by `MediaMuxer` capability. Three tiers: **API 29+** (scoped storage + `IS_PENDING`), **API 26–28** (legacy public-Movies via `File` + `MediaScannerConnection`, with optional `MediaMuxer(FileDescriptor)` to a private temp), **API 24–25** (legacy public-Movies via `File` + `MediaScannerConnection`, `MediaMuxer(String)` only). |
| 2 | Manifest `pendingUri` was the universal commit point. Pre-29 has no pending-row concept, so the field was meaningless on those tiers. | `pendingUri` is **only** populated on API 29+. Pre-29 tiers persist a `publicTargetPath` field instead, with file-rename / move as the commit point. Cold-launch recovery is API-tiered. |
| 3 | "Crash-safe pending recovery" was framed as one path; pre-29 needed a different recovery model entirely (no system to garbage-collect orphan rows for us). | Three explicit recovery paths, one per tier. Pre-29 recovery operates on the file system and `MediaScanner`; API 29+ operates on `MediaStore` rows. |
| 4 | Lint rule "manifest write of `pendingUri` must precede `MediaMuxer.start()`" was unconditional. | The rule is gated to API 29+ code paths. Pre-29 code paths must not write `pendingUri` (would be misleading). New rule: pre-29 paths must write `publicTargetPath` before the temp→public commit step. |

The **risk register entries C7 and C16** are rewritten in §2 below to reflect the tiered model. **C16's storage-API note is now explicit.**

---

## How to Use This Roadmap

(unchanged from v2/v3/v4)

---

## 1. System Overview

(unchanged from v4 except the *Validated Assumption* on `MediaMuxer` is replaced)

### Replaced Validated Assumption (Storage / MediaStore)

- `MediaStore.MediaColumns.IS_PENDING` exists **only** on API ≥ 29. It must not be used on API 24–28.
- `MediaMuxer(FileDescriptor, format)` exists on API ≥ 26.
- `MediaMuxer(String path, format)` exists on all supported API levels (≥ 18).
- On API 24–28, writing to public `Movies/` requires `WRITE_EXTERNAL_STORAGE` (already declared with `android:maxSdkVersion="28"` in `AndroidManifest.xml`) and is followed by `MediaScannerConnection.scanFile(...)` to register the file with the gallery.
- On API ≥ 29, `WRITE_EXTERNAL_STORAGE` is ignored; scoped `MediaStore` insert is the only sanctioned path for app-owned media in shared collections.

---

## 2. Risk Register — Diff from v4

> Only entries that change. All other risks (C1, C2, C3, C5, C8, C9, C10, C11, C12, C13, C14, C15, C17, C18, C19) are unchanged from v4.

### C7 — Storage realism *(rewritten — tiered)*
Storage-budget reasoning is split by API tier because the export pipeline differs:

- **API 29+ (Tier 1).** Direct mux to a `MediaStore` pending row via `FileDescriptor`. Peak ≈ segments + final. No double copy.
- **API 26–28 (Tier 2).** Direct mux to private temp via `MediaMuxer(FileDescriptor)` (or `String`), then `File` copy to `Environment.DIRECTORY_MOVIES/Rova/`, then `MediaScannerConnection.scanFile`. Peak ≈ segments + private merged + (transient) public copy.
- **API 24–25 (Tier 3).** Same as Tier 2 but `MediaMuxer(String)` only. Peak budget identical to Tier 2.

Pre-flight reserves the right peak per tier. The estimator must consult `Build.VERSION.SDK_INT` and apply the corresponding budget.

### C16 — Result-bearing export with crash-safe recovery *(rewritten — tiered)*
Each tier has its own commit point and its own cold-launch recovery routine. See §1.7 for the full sequence per tier.

- **Tier 1 (API 29+) commit point:** `IS_PENDING = 0` after a successful `update()`. Manifest field: `pendingUri`.
- **Tier 2 / Tier 3 (API 24–28) commit point:** the public file at `Movies/Rova/<name>.mp4` exists with the expected size **and** `MediaScannerConnection.scanFile` has called back. Manifest field: `publicTargetPath`. (Pre-Q has no `IS_PENDING` to flip; the file presence + scan is the equivalent visibility transition.)

---

## 3. Scheduling Model

(unchanged from v4)

---

## 4. OEM Kill — Detection Strategy

(unchanged from v4)

---

## Phase 0 — Pre-Implementation Prerequisites

(unchanged from v4 except the MediaMuxer ADR is superseded)

- [ ] **ADR `docs/adr/0003-storage-export-tiered.md`** — supersedes v3's `0003-media-muxer-target.md`. Records the three-tier export model in §1.7 and explicitly notes `IS_PENDING` is API 29+. Old ADR is deprecated with a forward link.

(All other v4 Phase 0 items unchanged.)

---

## Phase 1 — Reliability Backbone

### 1.1 Foundation

(unchanged from v4)

### 1.2 Scheduler

(unchanged from v4)

### 1.3 Stop Path

(unchanged from v4)

### 1.4 Recording Lifecycle Robustness

(unchanged from v4)

### 1.5 Crash & Corrupt-Segment Recovery

(unchanged from v4 — except the cold-launch reconciliation now also drives the per-tier export recovery in §1.7)

### 1.6 Storage Realism *(rewritten — tiered)*

> Tier selection is by `Build.VERSION.SDK_INT` and is decided once at session start; it does not change mid-session.

| Tier | API range | Mux target | Public export step | Storage peak |
|---|---|---|---|---|
| 1 | 29+ (Android 10+) | `MediaMuxer(FileDescriptor)` directly to `MediaStore` pending row | `IS_PENDING = 0` via `update()` | segments + final |
| 2 | 26–28 (Android 8–9) | `MediaMuxer(FileDescriptor)` *or* `MediaMuxer(String)` to private temp | `File` copy to `Environment.DIRECTORY_MOVIES/Rova/` + `MediaScannerConnection.scanFile` | segments + private merged + (transient) public copy |
| 3 | 24–25 (Android 7.0–7.1) | `MediaMuxer(String)` to private temp | Same as Tier 2 | segments + private merged + (transient) public copy |

Pre-flight reserves the appropriate peak per tier. The estimator method signature gains a `tier: ExportTier` parameter; bitrates from C7 (4K=10 MB/s, FHD=2 MB/s, HD=1 MB/s, SD=0.5 MB/s) are unchanged.

**Acceptance:**
- On a Tier 1 device, a session sized to roughly fit (capture + final) succeeds; the same session fails pre-flight on Tier 2/3 if (capture + private merged + public copy) does not fit.
- Tier downgrade does not corrupt Tier 1 sessions: if a Tier 1 session is recovered on a downgraded build (e.g. uninstall/reinstall), recovery treats it correctly per the manifest's recorded tier (see §1.7 recovery rules).

### 1.7 MediaStore / Public Export — Three Tiered Paths *(rewritten)*

#### Manifest fields (added in v5)

```
exportTier:           "TIER1_API29_PLUS" | "TIER2_API26_28" | "TIER3_API24_25"
privateTempPath:      String?    // tiers 2 & 3, set before mux start
pendingUri:           String?    // tier 1 only, set before mux start
publicTargetPath:     String?    // tiers 2 & 3, set before file move/copy
mediaScanCompleted:   Boolean    // tiers 2 & 3
exportState:          "NOT_STARTED" | "MUXING" | "COPYING" | "FINALIZED" | "FAILED"
```

#### Tier 1 — API 29+ (scoped storage, `IS_PENDING` available)

**Export sequence:**
1. `ContentValues { DISPLAY_NAME, MIME_TYPE=video/mp4, RELATIVE_PATH=Movies/Rova, IS_PENDING=1 }`.
2. `contentResolver.insert(MediaStore.Video.Media.getContentUri("external_primary"), values)` → `pendingUri`.
3. **Manifest write commit point:** `pendingUri = uri`, `exportState = MUXING`. Persisted before any mux activity.
4. `contentResolver.openFileDescriptor(pendingUri, "w")` → `MediaMuxer(fd, MUXER_OUTPUT_MPEG_4)`.
5. Mux all segments.
6. On mux success: `ContentValues { IS_PENDING=0 }` → `contentResolver.update(pendingUri, values, null, null)`.
7. Manifest: `exportState = FINALIZED`, `terminated = COMPLETED`, `pendingUri` retained for forensics.
8. On mux runtime failure: `contentResolver.delete(pendingUri, null, null)`; manifest `exportState = FAILED`, `terminated_reason = MERGE_FAILED`, `pendingUri = null`.

**Cold-launch recovery (`SessionStore.recoverTier1Exports()`):**
- For every manifest with `exportTier = TIER1_API29_PLUS`, `pendingUri != null`, `exportState ∈ {MUXING, COPYING}`, `terminated != COMPLETED`:
  1. `contentResolver.openFileDescriptor(pendingUri, "r")`. If throws / returns null → row already cleaned by OS or external app: clear `pendingUri`, `exportState = FAILED`, `terminated_reason = MERGE_FAILED` (offer retry from segments).
  2. If row opens: read `MediaStore.Video.Media.SIZE`. Compare to estimated final size from manifest segment list. Try `MediaExtractor.setDataSource(fd)`.
  3. If extractor opens cleanly **and** size is within ±5 % of estimate: clear `IS_PENDING=0`, manifest `FINALIZED` / `COMPLETED`. ("Finalize previous export.")
  4. If extractor throws or size is far off: `contentResolver.delete(pendingUri, null, null)`; manifest `FAILED`; offer retry from segments.
  5. No segment is deleted before this routine completes.

**Limitations:**
- Only Tier 1 supports automatic completion of a partial export across crashes. Pre-29 tiers cannot disambiguate "in-flight copy" from "complete file" without external markers — see §1.7's tier-2/3 commit semantics.

#### Tier 2 — API 26–28 (legacy public-Movies, no `IS_PENDING`)

**Export sequence:**
1. Compute `publicTargetPath = Environment.getExternalStoragePublicDirectory(DIRECTORY_MOVIES) / "Rova" / "<name>.mp4"`.
2. Compute `privateTempPath = getExternalFilesDir("export") / "<sessionId>.mp4"`.
3. **Manifest write commit point A:** `privateTempPath = ...`, `publicTargetPath = ...`, `exportState = MUXING`.
4. `MediaMuxer(privateTempPath, MUXER_OUTPUT_MPEG_4)` (or `MediaMuxer(FileDescriptor)` opened from the same path on API 26+ — equivalent).
5. Mux all segments.
6. On mux success: manifest `exportState = COPYING`.
7. **Atomic-as-possible publish step:**
   - Ensure parent dir exists.
   - Copy `privateTempPath → publicTargetPath + ".part"`.
   - `File.renameTo` from `<name>.mp4.part` to `<name>.mp4` (atomic on the same filesystem).
8. `MediaScannerConnection.scanFile(context, [publicTargetPath], ["video/mp4"]) { _, uri -> manifest.mediaScanCompleted = true }`.
9. Manifest: `exportState = FINALIZED`, `terminated = COMPLETED`. Delete `privateTempPath` only after `mediaScanCompleted = true`.
10. On mux runtime failure: delete `privateTempPath` if present; manifest `exportState = FAILED`, `terminated_reason = MERGE_FAILED`, `publicTargetPath = null`.

**Cold-launch recovery (`SessionStore.recoverPreQExports()`, applies to Tiers 2 & 3):**
- For every manifest with `exportTier ∈ {TIER2, TIER3}`, `terminated != COMPLETED`, `exportState ∈ {MUXING, COPYING}`:
  1. **Case A — `publicTargetPath` exists with expected size:** the rename succeeded but a crash happened before `scanFile` callback. Run `MediaScannerConnection.scanFile`; on callback set `mediaScanCompleted=true`, manifest `FINALIZED` / `COMPLETED`. Delete `privateTempPath`.
  2. **Case B — `publicTargetPath` does not exist but a `<name>.mp4.part` file exists:** copy was in flight. Delete the `.part` file. Manifest `exportState = COPYING_INCOMPLETE`.
     - If `privateTempPath` exists and `MediaExtractor` can open it: retry copy from §1.7 step 7.
     - If `privateTempPath` is missing or unreadable: manifest `FAILED`, offer retry from segments.
  3. **Case C — neither public nor `.part` exists, only `privateTempPath` exists and is readable:** mux completed but copy never started. Resume from §1.7 step 7.
  4. **Case D — nothing exists or `privateTempPath` is unreadable:** manifest `FAILED`, `terminated_reason = MERGE_FAILED`, retry-from-segments offered.
- No segment is deleted until this recovery routine completes.

**Limitations:**
- Pre-Q has no `IS_PENDING`; the file at `publicTargetPath` becomes visible to the gallery as soon as `MediaScanner` runs. The `.part` extension keeps the file out of the scanner's normal video matchers between copy start and `renameTo`. If the scanner runs early (other app triggers a scan), the user may briefly see an in-progress file — accepted limitation.
- `MediaScannerConnection.scanFile` is fire-and-forget; if the callback never returns (rare), `mediaScanCompleted` may stay `false`. Recovery routine re-runs `scanFile` on next launch (idempotent).
- `WRITE_EXTERNAL_STORAGE` is required on API ≤ 28 (already declared). Permission must be granted before Tier 2 export begins; if denied, manifest `FAILED` with reason `WRITE_EXTERNAL_STORAGE_DENIED` and the merged file is retained at `privateTempPath` for retry.

#### Tier 3 — API 24–25 (no FileDescriptor mux, legacy public-Movies)

**Export sequence:**
- Identical to Tier 2 except step 4 must use `MediaMuxer(privateTempPath, MUXER_OUTPUT_MPEG_4)` (the `String` constructor). `MediaMuxer(FileDescriptor)` is API 26+ and must not be used.

**Cold-launch recovery:** identical to Tier 2 (`SessionStore.recoverPreQExports()` covers both tiers).

**Limitations:**
- Same as Tier 2.
- No additional Tier 3-specific limitations; the only difference from Tier 2 is the muxer constructor.

#### Cross-tier acceptance criteria (consolidated from C16 + this section)

| Crash point | Tier 1 expectation | Tier 2 / 3 expectation |
|---|---|---|
| After `insert()` / before manifest commit | n/a (commit precedes insert in v5 by definition — manifest write of `pendingUri` is step 3, after `insert`; if app dies between step 2 and step 3 the row is orphaned but recovery scans `MediaStore` for orphans owned by the app and deletes them — see "Tier 1 startup orphan sweep" below) | n/a (no insert yet) |
| After manifest commit, before `mux.start()` | Recovery deletes pending row, retry from segments offered | Recovery resumes from step 4 (re-mux from segments) |
| Mid-mux | Recovery deletes pending row | Recovery deletes `privateTempPath`, re-mux from segments |
| After mux, before publish (Tier 1: `IS_PENDING=0`; Tier 2/3: `renameTo` of `.part`) | Recovery validates pending-row bytes; finalize if intact, delete + retry if not | Recovery resumes from step 7 (copy + rename) |
| After publish, before scan callback (Tier 2/3 only) | n/a | Recovery re-runs `MediaScannerConnection.scanFile`; idempotent |
| After full success | n/a (already `COMPLETED`) | n/a |

#### Tier 1 — startup orphan sweep (covers the "insert before manifest commit" gap)

On cold launch, before any session-recovery work:
1. Query `MediaStore.Video.Media.getContentUri("external_primary")` filtered by `OWNER_PACKAGE_NAME = packageName AND IS_PENDING = 1`.
2. For each returned row, check whether any session manifest references its URI as `pendingUri`.
3. Rows referenced by a manifest are handled by `recoverTier1Exports()`.
4. Rows **not** referenced by any manifest are orphans (insert succeeded, manifest commit never happened): `contentResolver.delete(uri, null, null)`.

This sweep is API-29+ only and runs alongside (not before) `SessionStore.scanOrphans()`.

### 1.8 Wakelock Discipline

(unchanged from v4)

---

## Phase 2 — User-Visible Reliability

(unchanged from v4 except the export-failure UX copy gains tier-specific affordances)

- [ ] **Tier 1 retry affordance.** On Tier 1 export failure with retained segments: "Saved locally — Retry export" button re-runs the export pipeline with a fresh `pendingUri`.
- [ ] **Tier 2/3 retry affordance.** On Tier 2/3 export failure with `privateTempPath` intact: "Saved locally — Copy to gallery" button re-runs steps 7–9 only, no re-mux. Requires `WRITE_EXTERNAL_STORAGE`; if revoked, prompt for grant before proceeding.
- [ ] **Tier 2/3 permission gate.** If `WRITE_EXTERNAL_STORAGE` is denied at session start on API ≤ 28, show an explicit "Gallery export disabled — Grant Storage permission to enable" banner before recording begins. Recording itself does not require this permission (`getExternalFilesDir` is unrestricted on all tiers).

(All other v4 Phase 2 items unchanged.)

---

## Phase 3 — Maintainability

(unchanged from v4 except the test matrix is expanded for tiered export)

- [ ] **`MediaStoreExporter` per-tier tests:**
  - Tier 1: success; insert fail; mux fail; `IS_PENDING=0` update fail; recovery from each crash point in the cross-tier acceptance table; orphan-sweep deletes unreferenced pending rows.
  - Tier 2: success; mux fail; copy fail; rename fail; scan callback never fires (idempotency); `WRITE_EXTERNAL_STORAGE` denied; recovery from each crash point.
  - Tier 3: same as Tier 2 with `MediaMuxer(String)` confirmed (assert no `FileDescriptor` ctor used in this code path).
- [ ] **Lint rule** — `MediaStore.MediaColumns.IS_PENDING` references must be inside an `if (Build.VERSION.SDK_INT >= 29)` guard or in a Tier 1-only file.

(All other v4 Phase 3 items unchanged.)

---

## Phase 4 — Polish & Ship

(unchanged from v4 except the OEM matrix QA must include all three tiers)

- [ ] **Export QA across tiers:** at least one device per tier in the OEM matrix exercises the full export path including a forced crash mid-export and recovery on next launch.

(All other v4 Phase 4 items unchanged.)

---

## Out of Scope for v1.0 *(updated for storage tiers)*

- (All v4 out-of-scope items retained.)
- **`IS_PENDING` on API 24–28.** Does not exist; will not be used. Pre-29 export uses `File` + `MediaScannerConnection` exclusively.
- **`MediaMuxer(FileDescriptor)` on API 24–25.** Does not exist; will not be used.
- **Atomic visibility of in-flight gallery exports on pre-29.** Mitigated by `.part` filename, but not guaranteed if a third party scans aggressively.

---

## Lint / CI Rules Summary *(updated)*

> Additions in v5 marked with **(v5)**. v4 rules retained.

| Rule | Reason |
|---|---|
| No `PendingIntent.getService` in alarm-scheduling code | v3 |
| No `bindService(BIND_AUTO_CREATE)` in STOP/tick receivers | v3 |
| No `SurfaceTexture#detachFromGLContext()` anywhere | v4 |
| No `peekService` as the only aliveness check in receivers | v4 |
| Manifest write of `pendingUri` must precede `MediaMuxer.start()` **on Tier 1 (API 29+) code paths only** | v4 (gated in v5) |
| **(v5)** Manifest write of `publicTargetPath` must precede the temp→public commit step on Tiers 2 & 3 | v5 |
| **(v5)** `MediaStore.MediaColumns.IS_PENDING` must be guarded by `Build.VERSION.SDK_INT >= 29` | v5 |
| **(v5)** `MediaMuxer(FileDescriptor, ...)` must be guarded by `Build.VERSION.SDK_INT >= 26` | v5 |
| **(v5)** `MediaScannerConnection.scanFile` is the public-visibility step for Tiers 2 & 3; not used on Tier 1 | v5 |

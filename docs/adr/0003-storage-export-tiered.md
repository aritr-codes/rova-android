# ADR 0003 — Tiered Storage / Public Export

- **Status:** Accepted (amended 2026-04-30 — see "FD Mode Amendment (Tier 1)")
- **Date:** 2026-04-25 (amended 2026-04-30)
- **Phase:** 0 (pre-implementation prerequisite); FD-mode amendment lands with Phase 1.7 implementation
- **Supersedes:** none in repo (formerly drafted as `0003-media-muxer-target.md` in v3; superseded in-flight by v5 before either was committed)
- **Superseded by:** —
- **Related:** ROADMAP_v6.md §1.6 (Storage Realism), §1.7 (MediaStore / Public Export — Three Tiered Paths), risks **C7**, **C16**

---

## Context

Rova merges per-segment MP4s into a single output and publishes it to the user's gallery. Three platform constraints split the export into three tiers — and a fourth, recovered late in v6, splits Tier 1 again by API for the recovery query.

| Constraint | Cutover | Effect |
|---|---|---|
| `MediaMuxer(FileDescriptor, ...)` constructor | API 26 | Below 26: only the `String` (file path) constructor exists. Cannot mux directly into a `MediaStore` pending row. |
| Scoped storage with `IS_PENDING` | API 29 | At/above 29: insert pending row → write via `openFileDescriptor` → flip `IS_PENDING=0`. Below 29: legacy `Environment.getExternalStoragePublicDirectory(DIRECTORY_MOVIES)` + `MediaScannerConnection.scanFile`. |
| `MediaStore.setIncludePending(uri)` | API 29 only | Required at API 29 to enumerate pending rows; deprecated and replaced at API 30. |
| `Bundle` query-args with `QUERY_ARG_MATCH_PENDING` | API 30+ | The replacement for `setIncludePending` from R onward. |

`MediaStore.query()` filters pending items out of results by default. Recovery code that lists pending rows owned by the app must apply the per-API pending-visibility mechanism above; direct-URI ops (`openFileDescriptor`/`update`/`delete` by specific URI) are not subject to the filter.

`minSdk = 24` means all three storage tiers are reachable in production.

## Decision

### Tier model — fixed at session start, recorded in manifest

| Tier | API range | Mux target | Public publish | Manifest commit point |
|---|---|---|---|---|
| **Tier 1** | 29+ | `MediaMuxer(FileDescriptor)` opened from `contentResolver.openFileDescriptor(pendingUri, "w")` | `IS_PENDING=0` via `update(pendingUri, ...)` | `pendingUri` written **before** `MediaMuxer.start()` |
| **Tier 2** | 26–28 | `MediaMuxer(FileDescriptor)` (or `MediaMuxer(String)` — equivalent) to `getExternalFilesDir("export")/<sessionId>.mp4` (private temp) | Copy to `Environment.DIRECTORY_MOVIES/Rova/<name>.mp4` via `<name>.mp4.part` + `renameTo`, then `MediaScannerConnection.scanFile` | `privateTempPath` and `publicTargetPath` written **before** mux start; `mediaScanCompleted=true` written **after** scan callback |
| **Tier 3** | 24–25 | `MediaMuxer(String)` only — `MediaMuxer(FileDescriptor)` is API 26+ and forbidden here | Same as Tier 2 | Same as Tier 2 |

Tier is selected by `Build.VERSION.SDK_INT` at session start, recorded in the manifest as `exportTier`, and **never reinterpreted**. A reinstall onto a downgraded build still reads Tier 1 sessions as Tier 1 from the manifest and applies Tier 1 recovery, even though the running build can no longer create new Tier 1 sessions.

### Manifest fields

```
exportTier:           "TIER1_API29_PLUS" | "TIER2_API26_28" | "TIER3_API24_25"
privateTempPath:      String?    // tiers 2 & 3 only — set before mux start
pendingUri:           String?    // tier 1 only — set before mux start (commit point)
publicTargetPath:     String?    // tiers 2 & 3 — set before file move/copy
mediaScanCompleted:   Boolean    // tiers 2 & 3
exportState:          "NOT_STARTED" | "MUXING" | "COPYING" | "FINALIZED" | "FAILED"
```

### Pending visibility (API 29 vs 30+) — recovery and orphan-sweep queries

`MediaStore.query()` hides pending items by default. Code that enumerates the app's own pending rows must make pending visibility explicit per API:

- **API 29 (single-API path):** wrap the query Uri with `MediaStore.setIncludePending(uri)` before calling `query()`.
- **API 30+:** `setIncludePending` is deprecated. Use the `Bundle` overload of `query()` and set `MediaStore.QUERY_ARG_MATCH_PENDING` to:
  - `MediaStore.MATCH_ONLY` for orphan-sweep queries (pending-only).
  - `MediaStore.MATCH_INCLUDE` for any future query that must see both pending and non-pending rows.
- **Direct URI ops:** `openFileDescriptor(pendingUri, ...)`, `update(pendingUri, ...)`, `delete(pendingUri, ...)` are **not** subject to the pending filter for the owner package — no flag required. Per-session recovery (`recoverTier1Exports()`) uses these direct ops on the manifest's recorded `pendingUri` and works without the include-pending flag.

The orphan sweep — which addresses the gap "insert succeeded, manifest commit never happened" — is the only path that **lists** pending rows; it must apply the per-API flag or it silently returns zero rows.

### Recovery routing

On every cold launch, before any cleanup pass:

1. For each manifest with `exportState ∈ {MUXING, COPYING}` and `terminated != COMPLETED`:
   - **Tier 1:** route to `recoverTier1Exports()` — addresses the row by `pendingUri`, decides finalize-or-discard from extractor health and size delta.
   - **Tier 2/3:** route to `recoverPreQExports()` — case analysis on existence of `publicTargetPath`, `<name>.mp4.part`, `privateTempPath`.
2. **Tier 1 orphan sweep:** enumerate the app's pending rows with the per-API include-pending flag; cross-reference the set of `pendingUri` values across all manifests; delete unreferenced rows. Idempotent.
3. No segment is deleted by any cleanup pass until both routines return.

### Forbidden combinations

- `MediaMuxer(FileDescriptor)` at `SDK_INT < 26`. Lint enforces.
- `IS_PENDING` at `SDK_INT < 29`. Lint enforces.
- `MediaStore.setIncludePending(uri)` at `SDK_INT != 29` (deprecated 30+; non-existent <29). Lint enforces.
- `MediaStore.QUERY_ARG_MATCH_PENDING` at `SDK_INT < 30`. Lint enforces.
- `MediaScannerConnection.scanFile` on Tier 1 (the row is in `MediaStore` already; scanning would race with `IS_PENDING=0`). Lint enforces.
- `ContentResolver.query` against `MediaStore.Video.Media` in the recovery / orphan-sweep code path **without** the per-API pending-visibility flag (v6). Lint enforces with both grep and unit-test assertion.
- `openFileDescriptor(pendingUri, "w")` for the `MediaMuxer(FileDescriptor)` constructor on Tier 1. Mode `"w"` is non-seekable on some providers; muxing must use mode `"rw"`. See "FD Mode Amendment (Tier 1)" below. Lint enforces.

## FD Mode Amendment (Tier 1)

**Amends:** ROADMAP_v5.md §1.7 step 4 and ROADMAP_v6.md §1.7 *(unchanged-from-v5 inheritance)*. The literal `"w"` mode in those documents is **superseded by `"rw"`** for Tier 1 pending-row muxing. ROADMAP_v5/v6 documents themselves are not edited; this ADR is the canonical record.

### Decision

Tier 1's `MediaMuxer(FileDescriptor)` opens the pending row's file descriptor with mode `"rw"`, **not** `"w"`:

```kotlin
val pfd = contentResolver.openFileDescriptor(pendingUri, "rw")
val muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
```

### Why

Per the Android `ContentResolver.openFileDescriptor` documentation, mode `"w"` does **not** guarantee a seekable, regular-file-backed descriptor — providers may legitimately return a wrapper backed by a pipe or socket. `MediaMuxer` rewrites the MP4 `moov` atom at `stop()`, which requires `lseek` on the underlying fd. A non-seekable `"w"` descriptor produces a corrupt MP4 (no playable moov) on those providers, with no exception at mux time — the failure surfaces only when the user tries to play the file.

Mode `"rw"` carries an implicit guarantee of a regular-file-backed seekable descriptor on every implementation. `"rwt"` (truncate) is **not** required because `contentResolver.insert(...)` returns a fresh empty pending row.

### Scope

This amendment is Tier 1-only. Tier 2 and Tier 3 mux to a private file via `MediaMuxer(String, ...)` and never open a `MediaStore` file descriptor.

### Lint enforcement

A new lint task (lands with the Phase 1.7 `Tier1Exporter` commit) forbids the literal `"w"` argument to any `openFileDescriptor(pendingUri, ...)` call site in `Tier1Exporter.kt`.

### Reference

- Android SDK reference: `android.content.ContentResolver#openFileDescriptor(Uri, String)` — mode argument seekability semantics.

## Consequences

### Positive

- One mental model per tier, frozen in the manifest. Recovery does not have to second-guess what the running build can do.
- The pending-visibility split (29 vs 30+) is captured as a single rule applied to one code path (orphan sweep), not scattered across the codebase.
- Tier 2/3 publish-and-scan flow uses `<name>.mp4.part` + `renameTo` so a partial copy is invisible to the gallery scanner — recoverable on next launch.

### Negative

- Three code paths to maintain. Mitigated: per-tier exporters share an `ExportPipeline` interface; the differences are localized.
- `MediaScannerConnection.scanFile` is fire-and-forget. If its callback never fires (rare), `mediaScanCompleted` stays `false` and the recovery routine re-runs `scanFile` on next launch — idempotent.
- API reinstall after package-name change strands previously-inserted pending rows beyond app reach. Accepted: the OS reclaims them via the pending TTL (7 days).

### Neutral

- `WRITE_EXTERNAL_STORAGE` is required only at API ≤ 28 (Tiers 2 & 3); already declared with `maxSdkVersion="28"` in the manifest.

## Acceptance Criteria

- ADR file present at `docs/adr/0003-storage-export-tiered.md`.
- Manifest field schema (`exportTier`, `privateTempPath`, `pendingUri`, `publicTargetPath`, `mediaScanCompleted`, `exportState`) implemented in Phase 1.1's `SessionManifest` data class.
- Storage estimator (Phase 1.6, C7) selects tier from `Build.VERSION.SDK_INT` once per session.
- Three exporter implementations (Phase 1.7) gate `IS_PENDING`, `MediaMuxer(FileDescriptor)`, `setIncludePending`, and `QUERY_ARG_MATCH_PENDING` per the lint rules above.
- The Tier 1 orphan-sweep test matrix from Phase 3 (API 29 path, API 30+ path, filter regression, idempotency, reference protection) covers the pending-visibility split.

## References

- ROADMAP_v6.md §1.7 (rewritten Tier 1 orphan sweep).
- ROADMAP_v5.md §1.6, §1.7 (tier model definitions — unchanged in v6).
- Risks **C7** (storage realism), **C16** (result-bearing export with crash-safe recovery).
- Android docs: `MediaStore.setIncludePending`, `MediaStore.QUERY_ARG_MATCH_PENDING`, `MediaMuxer` constructors, `MediaScannerConnection.scanFile`.

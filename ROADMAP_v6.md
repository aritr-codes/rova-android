# Rova — Production Readiness Roadmap (v6)

> Surgical revision of `ROADMAP_v5.md`. Only the **Tier 1 (API 29+) MediaStore pending-row recovery** logic is changed: pending items are hidden by default in `MediaStore` queries, so v5's orphan sweep would miss them. v5's Tier 2 / Tier 3 logic, the rest of §1.7, and every other section (scheduling, service model, surface lifecycle, STOP path, force-kill detection, drift policy, wakelock discipline, risks C1 / C2 / C3 / C8 / C9 / C10 / C11 / C12 / C13 / C14 / C15 / C17 / C18 / C19) are **unchanged from v5** and remain authoritative as written there.

---

## What Changed from v5

| # | v5 problem | v6 fix |
|---|---|---|
| 1 | The Tier 1 orphan sweep used `contentResolver.query(...)` filtered by `IS_PENDING = 1`. **`MediaStore` filters pending items out of query results by default**, so this query would return zero rows even when orphans exist — orphans would silently leak. | Pending visibility is now made explicit per API level. **API 29:** `MediaStore.setIncludePending(uri)` is applied to the query Uri. **API 30+:** `setIncludePending` is deprecated; use the `Bundle` query-args overload with `QUERY_ARG_MATCH_PENDING = MATCH_ONLY` for the orphan sweep (and `MATCH_INCLUDE` for any future query that must see both pending and non-pending). |
| 2 | The per-session recovery (`recoverTier1Exports()`) was reasoned to work because it addresses rows by their specific `pendingUri`. This is true for `openFileDescriptor` / `update` / `delete`, but v5 was implicit about it. | v6 explicitly states the rule: **direct URI operations** (`openFileDescriptor`, `update`, `delete` by URI) work on the owner's pending rows without the pending filter; **`query()` calls** must apply the per-API pending-visibility flag or they will silently return empty. The lint rule below enforces this. |
| 3 | No defensive guarantee that the pending-visibility flag was applied. | New lint rule: any `ContentResolver.query` against a `MediaStore.Video.Media` collection in the orphan-sweep / recovery code path must either apply `MediaStore.setIncludePending(uri)` (API 29) or pass `QUERY_ARG_MATCH_PENDING` (API 30+). CI check verifies. |

Tier 2 and Tier 3 (API 24–28) recovery logic is **unchanged from v5** — those tiers do not have `IS_PENDING` and operate on the file system + `MediaScannerConnection`.

---

## How to Use This Roadmap

(unchanged from v2/v3/v4/v5)

---

## 1. System Overview

(unchanged from v5 except one *Validated Assumption* added)

### Added Validated Assumption (Pending Visibility)

- `MediaStore` `query()` calls **filter out pending items by default**. To list pending rows owned by the app:
  - **API 29 (Q):** apply `MediaStore.setIncludePending(uri)` to the query Uri.
  - **API 30+ (R and later):** `setIncludePending` is deprecated; pass a `Bundle` query-args with `QUERY_ARG_MATCH_PENDING` set to `MATCH_ONLY` (pending-only) or `MATCH_INCLUDE` (both).
- Direct-URI operations on pending rows owned by the calling package are **not** subject to the pending filter — `contentResolver.openFileDescriptor(pendingUri, "w")`, `update(pendingUri, ...)`, `delete(pendingUri, ...)` work without any include-pending flag.

---

## 2. Risk Register — Diff from v5

> Only entries that change. All other risks (C1, C2, C3, C5, C7, C8, C9, C10, C11, C12, C13, C14, C15, C17, C18, C19) are unchanged from v5.

### C16 — Result-bearing export with crash-safe recovery *(Tier 1 sub-clause updated)*
The **Tier 1 startup orphan sweep** must apply the pending-visibility flag. v5's "query for `IS_PENDING = 1`" wording is replaced; the per-API mechanism is now explicit. Tier 2 and Tier 3 paths are unchanged.

---

## 3. Scheduling Model

(unchanged from v5)

---

## 4. OEM Kill — Detection Strategy

(unchanged from v5)

---

## Phase 0 — Pre-Implementation Prerequisites

(unchanged from v5 except the storage ADR is updated)

- [ ] **ADR `docs/adr/0003-storage-export-tiered.md`** — append §"Pending Visibility (API 29 vs 30+)" describing the per-API pending-filter mechanism in §1.7 below. ADR remains the single record of the tiered export design.

(All other v5 Phase 0 items unchanged.)

---

## Phase 1 — Reliability Backbone

### 1.1 Foundation

(unchanged from v5)

### 1.2 Scheduler

(unchanged from v5)

### 1.3 Stop Path

(unchanged from v5)

### 1.4 Recording Lifecycle Robustness

(unchanged from v5)

### 1.5 Crash & Corrupt-Segment Recovery

(unchanged from v5)

### 1.6 Storage Realism

(unchanged from v5 — three tiers, peak budgets, `Build.VERSION.SDK_INT`-driven tier selection)

### 1.7 MediaStore / Public Export — Three Tiered Paths

#### Manifest fields *(unchanged from v5)*

(Same fields: `exportTier`, `privateTempPath`, `pendingUri`, `publicTargetPath`, `mediaScanCompleted`, `exportState`.)

#### Tier 1 — API 29+ *(orphan-sweep and recovery query rules rewritten; sequence and commit point unchanged)*

**Export sequence:** unchanged from v5 (steps 1–8 identical). Manifest write of `pendingUri` (step 3) remains the commit point. `IS_PENDING = 0` via `update()` remains the finalize step.

**Per-session cold-launch recovery (`SessionStore.recoverTier1Exports()`):** unchanged from v5 in shape. The routine addresses rows by their specific `pendingUri` via `openFileDescriptor` / `update` / `delete` — these operations are not subject to the pending filter for the owner package, so v5's logic is correct as written. Only the *orphan sweep* (which lists rows) needs the new mechanism.

**Tier 1 — Startup orphan sweep (rewritten — pending-visibility-aware):**

The sweep covers the gap "insert succeeded, manifest commit never happened": a pending row exists in `MediaStore` but no manifest references it. Such rows are invisible to default `query()` calls and must be enumerated with explicit include-pending.

**API 29 (single-API path, before `Q_BUNDLE`-style query-args were added):**

```
val baseUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
val pendingAwareUri = MediaStore.setIncludePending(baseUri)
val projection = arrayOf(
    MediaStore.Video.Media._ID,
    MediaStore.Video.Media.OWNER_PACKAGE_NAME,
    MediaStore.Video.Media.IS_PENDING
)
val selection = "${MediaStore.Video.Media.OWNER_PACKAGE_NAME} = ? " +
                "AND ${MediaStore.Video.Media.IS_PENDING} = 1"
val selectionArgs = arrayOf(packageName)
contentResolver.query(pendingAwareUri, projection, selection, selectionArgs, null)
```

**API 30+ (`setIncludePending` is deprecated; use `Bundle` query-args):**

```
val baseUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
val args = Bundle().apply {
    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
    putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
              "${MediaStore.Video.Media.OWNER_PACKAGE_NAME} = ?")
    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(packageName))
}
contentResolver.query(baseUri, projection, args, null)
```

**Sweep procedure (both API paths produce the same `Cursor` shape):**

1. Run the appropriate query for the device's API level.
2. For each row, build the row's content URI: `ContentUris.withAppendedId(baseUri, _id)`.
3. Look up the row's URI in the set of `pendingUri` values currently referenced by any session manifest (loaded once at the start of the sweep).
4. **Referenced rows** are handled by `recoverTier1Exports()` (per-session recovery). The sweep does **not** touch them.
5. **Unreferenced rows** are orphans: `contentResolver.delete(rowUri, null, null)`. Direct `delete` by URI works on the owner's pending rows without any pending-visibility flag.
6. The sweep is idempotent — if the orphan was already deleted (e.g. by another call), `delete` returns 0 and the sweep continues.
7. The sweep runs alongside (not before) `SessionStore.scanOrphans()` (the manifest-side scan); no segment is deleted by any cleanup pass until both sweeps complete.

**Edge cases:**

- **Volume not mounted (rare on primary external):** the query throws or returns null. The sweep logs and skips; orphan rows on a transiently-unavailable volume will be cleaned on a later launch.
- **Row owned by the app but not pending:** filtered out by `IS_PENDING = 1` (API 29) / `MATCH_ONLY` (API 30+). These are not orphans — they are completed exports.
- **Row with `OWNER_PACKAGE_NAME` null** (possible in some sideloaded states): excluded by the selection. Cannot safely be deleted because ownership is ambiguous.
- **Permissions:** the owner package can always enumerate its own pending rows under both `setIncludePending` and `MATCH_ONLY` — no `MANAGE_EXTERNAL_STORAGE` or all-files access is required.

**Acceptance:**

- On API 29 device: simulate "insert without manifest commit" by inserting a `MediaStore.Video.Media` row with `IS_PENDING=1` and no manifest entry → next launch's orphan sweep deletes it. Verify by re-running the sweep with `setIncludePending` + `IS_PENDING=1` and confirming zero matching rows.
- On API 30+ device: same scenario → verify `Bundle` query with `QUERY_ARG_MATCH_PENDING=MATCH_ONLY` returned the orphan, and a second sweep returns empty.
- On API 30+ device: a *referenced* pending row (manifest's `pendingUri`) is **not** deleted by the sweep; it is left for `recoverTier1Exports()`.
- On API 29 device: same with `setIncludePending`.

**Limitations:**

- The sweep relies on `OWNER_PACKAGE_NAME` matching. On API 29+, the owner is set automatically on insert. App reinstall typically preserves the package name; if it does not (signing-key change, package rename), previously-inserted pending rows become orphans the app can no longer claim. This is accepted — such rows are subject to the OS's own pending TTL (7 days) and will be reclaimed by `MediaStore`.

#### Tier 2 — API 26–28

(unchanged from v5)

#### Tier 3 — API 24–25

(unchanged from v5)

#### Cross-tier acceptance criteria *(unchanged from v5; the Tier 1 row in the table now resolved against the new sweep)*

The "After `insert()` / before manifest commit" cell for Tier 1 in v5's table referenced the orphan sweep. With v6 the sweep is correctly pending-visibility-aware and the cell's expectation ("recovery scans `MediaStore` for orphans owned by the app and deletes them") is now achievable as written.

### 1.8 Wakelock Discipline

(unchanged from v5)

---

## Phase 2 — User-Visible Reliability

(unchanged from v5)

---

## Phase 3 — Maintainability

(unchanged from v5 except the test matrix gains pending-visibility cases)

- [ ] **`MediaStoreExporter` / orphan sweep — pending-visibility tests (new in v6):**
  - **API 29 path:** assert query Uri is wrapped via `MediaStore.setIncludePending(uri)`. Insert a synthetic pending row, run sweep, assert it was returned and deleted.
  - **API 30+ path:** assert `Bundle` query uses `QUERY_ARG_MATCH_PENDING = MATCH_ONLY`. Same insert/sweep/delete assertion.
  - **Filter regression test:** a non-pending row owned by the app and a non-owned pending row must both be excluded from the sweep.
  - **Idempotency test:** running the sweep twice in a row must succeed with zero rows in the second pass.
  - **Reference-protection test:** a pending row whose URI is in a manifest's `pendingUri` is **not** deleted by the sweep.

(All other v5 Phase 3 items unchanged.)

---

## Phase 4 — Polish & Ship

(unchanged from v5; OEM matrix QA already requires per-tier export coverage. v6 adds an explicit assertion that the Tier 1 sweep verification step uses the per-API pending-visibility flag.)

---

## Out of Scope for v1.0

(unchanged from v5)

---

## Lint / CI Rules Summary *(updated)*

> v6 additions marked with **(v6)**. All v3/v4/v5 rules retained.

| Rule | Reason |
|---|---|
| No `PendingIntent.getService` in alarm-scheduling code | v3 |
| No `bindService(BIND_AUTO_CREATE)` in STOP/tick receivers | v3 |
| No `SurfaceTexture#detachFromGLContext()` anywhere | v4 |
| No `peekService` as the only aliveness check in receivers | v4 |
| Manifest write of `pendingUri` must precede `MediaMuxer.start()` on Tier 1 (API 29+) code paths only | v4 / v5 |
| Manifest write of `publicTargetPath` must precede the temp→public commit step on Tiers 2 & 3 | v5 |
| `MediaStore.MediaColumns.IS_PENDING` must be guarded by `Build.VERSION.SDK_INT >= 29` | v5 |
| `MediaMuxer(FileDescriptor, ...)` must be guarded by `Build.VERSION.SDK_INT >= 26` | v5 |
| `MediaScannerConnection.scanFile` is the public-visibility step for Tiers 2 & 3; not used on Tier 1 | v5 |
| **(v6)** Any `ContentResolver.query` against a `MediaStore.Video.Media` collection in the recovery / orphan-sweep code path **must** apply the per-API pending-visibility flag: `MediaStore.setIncludePending(uri)` on API 29 **or** `Bundle` query-args with `MediaStore.QUERY_ARG_MATCH_PENDING` on API 30+. CI grep + unit-test assertion. | v6 |
| **(v6)** `MediaStore.setIncludePending` calls must be guarded by `Build.VERSION.SDK_INT == 29` (deprecated on 30+; use `QUERY_ARG_MATCH_PENDING` instead). | v6 |
| **(v6)** `MediaStore.QUERY_ARG_MATCH_PENDING` must be guarded by `Build.VERSION.SDK_INT >= 30`. | v6 |

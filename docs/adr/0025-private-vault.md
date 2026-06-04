# ADR-0025 — Private vault: gallery-hidden recordings behind a device-credential lock (Track-B B5)

**Status:** Accepted

**Date:** 2026-06-04

**Deciders:** Rova owner

**Supersedes / amends:** none. Adds a vault membership orthogonal to the tiered public-export model (ADR-0003) and the SAF route (ADR-0024). The existing Tier 1/2/3/SAF rails are untouched; a vaulted recording bypasses all of them.

## Context

Track-B B5 asks to keep selected recordings **out of the device gallery** and behind an **in-app lock**. Rova already stages every merged recording in app-private storage (`getExternalFilesDir("videos")/<sessionId>/`) — a path the gallery, other apps, and the file manager never scan on a non-rooted device — before the export step publishes it to a public destination. "Hiding from the gallery" therefore reduces to **not publishing**: keep the merged file app-private and never insert it into MediaStore, never media-scan it, never write it to a public/SAF path. The lock reuses the OS device credential (fingerprint / face / pattern / PIN) via `BiometricPrompt`; Rova stores no custom password.

The owner confirmed: a global all-or-nothing toggle (not per-recording); no file encryption in v1 (privacy = app-private storage + in-app gate); auth gates *viewing the vault* and *turning the toggle off* (turning it on is free); recording stays frictionless; the user can move recordings into and out of the vault.

## Decision

### Mutable `vaultState`, frozen `vaultIntentAtStart` — kept separate from `exportTier`

`SessionManifest` gains a **frozen** `vaultIntentAtStart: Boolean` (set at session start from `RovaSettings.hideInVault`, drives export routing for a new recording so a crash mid-record still resolves to the vault) and a **mutable** `vaultState: VaultState` (`PUBLIC` / `VAULTING` / `VAULTED` / `UNVAULTING`) plus `vaultFilePath` (+ per-side `portraitVaultFilePath` / `landscapeVaultFilePath`). Schema bumped 6 → 7, additive + tolerant read (old manifests → `vaultIntentAtStart=false`, `vaultState=PUBLIC`).

`exportTier` stays **frozen** and unchanged — it records *how a recording would publish*. Vault membership is deliberately a separate, mutable axis so move in/out is a clean state transition that never corrupts the frozen tier or the recovery/cleanup logic that leans on tier stability. Modelling the vault as a 5th `ExportTier` value was rejected for exactly this reason: a "frozen" field cannot also be the thing a move mutates.

### `VaultExporter` — merge-to-private, never publish

A vault-intent session is dispatched ahead of the tier `when` by a guard at the top of the single `ExportPipeline.export(...)` entry: `if (vaultIntent) return exportVault(...)`. `VaultExporter` (pure seam, framework effects injected — the `Tier2Exporter` pattern) merges the segments straight to the app-private vault file and finalizes with `ExportState.FINALIZED` + `vaultState = VAULTED`. It performs **no** MediaStore insert, **no** `MediaScannerConnection`, **no** public-path write, **no** `IS_PENDING`. `VideoMerger.mergeSegments` stays inside `service/export/`, so `checkExportPipelineSingleEntry` is preserved; by never touching `IS_PENDING`/`resolver.query` it sits outside every pending-row gate.

The `Terminated.COMPLETED` terminal write stays owned by `RovaRecordingService.performMerge` (vault finalize uses `ExportState.FINALIZED` only), so `checkCompletedWriteOnlyFromPerformMerge` is preserved.

### `checkVaultExporterNoPublicPublish` (29th gate)

The core privacy invariant is mechanically enforced: a line-oriented tripwire over `VaultExporter.kt` fails `preBuild` on any reference to `MediaStore` / `MediaScannerConnection` / `insertPendingRow` / `scanAndWait` / `DIRECTORY_MOVIES` / `IS_PENDING` / `.insert(` / `getExternalStoragePublicDirectory`. Consistent with the existing gates: a structural tripwire, not a correctness proof — but it shuts the door on a future edit silently making a vaulted recording gallery-visible.

### Auth: device credential only, in-memory unlock, relock on background

`VaultAuthGate` wraps `BiometricPrompt` with `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` (fingerprint/face + automatic phone pattern/PIN fallback); API 24–27 fall back to `KeyguardManager.createConfirmDeviceCredentialIntent`. The pure path-selection (`vaultAuthPath`) and the unlock-state reducer (`VaultLockState`) are JVM-tested; the framework call is a thin wrapper. Unlock state is **in-memory only, never persisted** — re-auth on every vault entry and on app foreground, relocked on a `ProcessLifecycleOwner` `ON_STOP` observer (the same hook ADR-0021 uses for the camera) and on leaving the vault route. Auth gates (a) viewing/playing vault contents and (b) turning the toggle **ON → OFF**; turning it on is free; recording is never gated. If no device credential is enrolled, gallery-hiding still works but the in-app lock cannot engage — surfaced honestly, never presented as "protected."

### Move in/out — two-phase, crash-recoverable

`VaultMover` (pure orchestration, effects injected) flips membership through the intermediates so the manifest is written only **after** each destructive step verifies:
- **Into vault:** copy the merged file to app-private → manifest `VAULTING` → delete the public copy (Tier1 `resolver.delete`; Tier2/3 file delete **+ media rescan** to drop the stale gallery index; SAF `DocumentFile.delete`) → manifest `VAULTED`.
- **Out of vault:** manifest `UNVAULTING` → publish the existing vault file via a copy-bytes-only path (no re-merge, so the single-entry gate is untouched) through the normal publisher at `currentExportTier(hasUsableSafFolder)` recomputed now → manifest `PUBLIC`, public field populated, `vaultFilePath` cleared.

Recovery keys off `vaultIntentAtStart` (record path) and `vaultState` (in-flight moves), never `exportTier`: an interrupted `VAULTING`/`UNVAULTING` resumes its direction on next cold launch. `ExportCleanupPredicate` treats any non-`PUBLIC` session with a `vaultFilePath` as a **kept artifact** (never an orphan to delete), without dropping any of its four existing decision tokens.

### Playback, screenshots, backup

Vaulted recordings play via a **FileProvider `content://`** URI (not `file://`, avoiding `FileUriExposedException`), reachable only from inside the unlocked vault. Vault list + vault player screens set `FLAG_SECURE` (no screenshots, no recents-thumbnail leak). The vault video directory is excluded from **Auto Backup + device transfer** so private recordings never sync off-device (manifests are *not* excluded — that would break post-restore recovery for non-vault sessions, and the sensitive payload is the video).

## Non-goals (v1)

No file encryption at rest (documented future extension — the `vaultState` + `vaultFilePath` model layers under a Keystore-gated cipher without reshape); no per-recording vault choice at record time; no auth at record time; no retroactive vaulting of existing recordings when the toggle flips (forward-only; existing recordings move only by explicit user action).

## Consequences

- A privacy vault with no change to the public-export rails — purely additive routing plus a membership axis.
- "Protected" is honest: app-private + biometric gate, **not** encryption — UI copy says so and never overpromises. A rooted phone or USB-debugging session can still pull the raw files; encryption is the future closer.
- Cost: move in/out does a full byte-copy (and, for move-out, a re-publish); acceptable for explicit user actions.
- The 29th gate locks the no-publish invariant shut for any future `VaultExporter` edit.

## Hard invariants preserved

- No existing `check*` gate edited; this adds the 29th (`checkVaultExporterNoPublicPublish`). `checkExportPipelineSingleEntry`, `checkExportCleanupPredicate`, `checkCompletedWriteOnlyFromPerformMerge`, and the pending-row visibility gates stay green.
- `exportTier` remains frozen and unchanged; vault membership is the only mutable destination axis.
- The muxer never targets a public/MediaStore/SAF descriptor on the vault path (always the local app-private file).
- House conventions: pure-helper extraction (`VaultExporter`, `VaultMover`, `VaultLockState`, `VaultAuthDecision`, `VaultListFilter`), thin framework seams (`VaultAuthGate`, `VaultAndroidOps`), ADR-clause → `check*` → `preBuild`.

## Implementation reference

`docs/superpowers/specs/2026-06-04-private-vault-design.md` and `docs/superpowers/plans/2026-06-04-private-vault.md`.

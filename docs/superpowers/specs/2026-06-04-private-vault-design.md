# Private Vault — Design Spec

**Date:** 2026-06-04
**Status:** Draft (owner review pending)
**Track:** B5 (Settings expansion / privacy)
**Related ADRs:** ADR-0003 (tiered export), ADR-0024 (SAF export destination), ADR-0005/0006 (recovery), proposed **ADR-0025 (private vault)**

---

## 1. Summary

Add a **private vault**: an opt-in mode and per-recording membership that keeps selected recordings **out of the device gallery** and **behind an in-app device-credential lock** (fingerprint / face / pattern / PIN). Vault recordings live only in Rova's app-private storage; they are never inserted into MediaStore, never media-scanned, and never written to a public or SAF folder while vaulted.

**v1 explicitly does NOT encrypt files at rest.** Privacy = app-private storage (invisible to the gallery, other apps, and the file manager on a non-rooted device) + an in-app biometric/credential gate on the vault UI. Encryption-at-rest is a documented future extension that layers onto this design without reshaping it.

## 2. Goals / Non-goals

**Goals**
- Global all-or-nothing Settings toggle: while ON, new recordings are vaulted.
- Vaulted recordings never appear in the device gallery on any export tier (API 24–37) or via SAF.
- In-app Vault section gated by the device credential; gate is re-checked on every entry and on app foreground.
- User can move existing recordings **into** the vault and vault recordings **out** to the gallery.
- Explicit Share / export-out remains available once the vault is unlocked.
- Crash-safety: no move ever loses a recording or silently leaves a "supposedly hidden" recording gallery-visible.

**Non-goals (v1)**
- File encryption at rest (future extension; see §12).
- Per-recording vault choice at record time (global toggle only).
- Auth at record time (recording stays frictionless / hands-free).
- Retroactively vaulting existing recordings when the toggle flips (forward-only for new recordings; existing ones move only via explicit user action).

## 3. Confirmed requirements (owner)

| # | Decision |
|---|----------|
| R1 | Global `hideInVault` toggle, all-or-nothing. ON → new recordings vaulted. |
| R2 | Vault = app-private storage + in-app biometric gate. **No encryption in v1.** |
| R3 | Auth via `BiometricPrompt(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`; fingerprint/face + automatic phone pattern/PIN fallback. No custom password. |
| R4 | Auth gates: (a) viewing/playing vault contents, (b) turning the toggle OFF. Turning ON is free. Recording is frictionless. |
| R5 | Re-auth on every vault entry and on app foreground. Unlock state in-memory only, never persisted. |
| R6 | Explicit Share / export-out allowed once unlocked. |
| R7 | User can move recordings into and out of the vault. |

## 4. Architecture decision: mutable membership, frozen tier

`SessionManifest.exportTier` stays **frozen at session start** (it records *how a recording would publish* — Tier1/2/3/SAF). Vault membership is a **separate, mutable** field so move in/out is a clean state transition that does not corrupt the frozen tier or its recovery/cleanup semantics.

**Rejected alternative:** adding `VAULT_PRIVATE` as a 5th `ExportTier`. The tier is frozen by contract, but vault membership changes after the fact — mutating a "frozen" field muddies its meaning and the `checkExportTierReadTolerant` / recovery invariants that lean on tier stability.

### 4.1 `VaultState` (per codex review — not a boolean)

```
enum VaultState { PUBLIC, VAULTING, VAULTED, UNVAULTING }
```

A plain `vaulted: Boolean` has a real crash window during moves (file deleted from public but manifest not yet flipped, or vice-versa). The four-state model makes every move a two-phase transition with a recoverable intermediate:

- `PUBLIC` — normal, gallery-visible (default for old manifests via tolerant read).
- `VAULTING` — move-in in progress; private copy exists, public copy may still exist. **Treated as hidden from History and as recoverable.**
- `VAULTED` — fully in the vault; no public copy; private file is the artifact of record.
- `UNVAULTING` — move-out in progress; public copy may exist but not yet confirmed; private file still present.

A freshly-recorded vault session (global toggle ON) finalizes **directly to `VAULTED`** — there is no public copy to remove, so no intermediate is needed on the record path. Intermediates exist only for moves.

## 5. Data model changes

### `SessionManifest` (schema 6 → 7, additive + tolerant read)

- `vaultIntentAtStart: Boolean = false` — **frozen** at session start from `settings.hideInVault`. Drives export routing for *new* recordings, exactly as `exportTier` is frozen for publish capability. Immutable for the session's life; move in/out does **not** touch it.
- `vaultState: VaultState = PUBLIC` — **mutable** membership (see §4.1). Set to `VAULTED` by `VaultExporter` on finalize; flipped by `VaultMover` on move in/out.
- `vaultFilePath: String? = null` (single-mode merged file, app-private)
- `portraitVaultFilePath: String? = null`, `landscapeVaultFilePath: String? = null` (P+L dual-recording, mirrors existing per-side fields)

Why both `vaultIntentAtStart` and `vaultState`: the intent is frozen (a crash mid-record must still resolve to the vault), while membership is mutable (a finished recording can later move out, and a public one can move in). Conflating them would either lose the crash guarantee or corrupt the frozen value on a move.

Tolerant read: a schema-≤6 manifest reads back `vaultIntentAtStart=false`, `vaultState=PUBLIC`, `vaultFilePath=null`. No breaking change (bumps are additive, matching the existing pattern).

`exportTier` unchanged. The existing public-destination fields (`pendingUri`, `publicTargetPath`, `safTargetDocUri`, per-side variants) are **null while `VAULTED`** and re-populated on move-out.

### `RovaSettings`

- `hideInVault: Boolean` (SharedPreferences key `hide_in_vault`, default `false`, backed up — genuine user preference).

## 6. Export flow

### 6.1 Record path (new recordings)

- At **session start** (where `exportTier` is frozen today, in `RovaRecordingService`), freeze `vaultIntentAtStart = settings.hideInVault` into the manifest. A crash therefore keeps the session private (recovery reads the frozen intent).
- `ExportPipeline.export()` gains a guard **above** the existing `when(tier)` dispatch:
  ```
  if (manifest.vaultIntentAtStart) return exportVault(...)
  ```
  The four existing tier branches are untouched.
- **`VaultExporter`** (in `service/export/`): merges segments via `VideoMerger.mergeSegments` straight to the app-private vault path, validates the output, writes `vaultFilePath` + `vaultState = VAULTED` + `exportState = FINALIZED`. It performs **no** MediaStore insert, **no** `MediaScannerConnection`, **no** public file write, **no** `IS_PENDING`. Because it routes through the single `export()` entry point and lives in `service/export/`, it satisfies `checkExportPipelineSingleEntry`; by never touching `IS_PENDING`/`resolver.query` it sidesteps every pending-row gate.

### 6.2 Recovery & cleanup (state dominates tier)

- `ExportRecoveryRunner`: a crashed, never-finalized session with `vaultIntentAtStart == true` re-merges to the vault path on next cold launch; it is **never** published. Recovery keys off `vaultIntentAtStart` (record path) and `vaultState` (in-flight moves), never `exportTier`.
- `ExportCleanupPredicate`: `vaultFilePath` is a **kept artifact** — a `VAULTED` session's file is never treated as a deletable orphan, and segments are not deleted until vault validation/finalization completes.
- **Intermediate-state recovery rules** (explicit):
  - `VAULTING` found at cold launch → resume: ensure private copy is valid, then delete any lingering public copy, then advance to `VAULTED`. Until then, hidden from History.
  - `UNVAULTING` found at cold launch → resume: re-attempt publish of the existing vault file; on confirmed publish advance to `PUBLIC`; else remain `UNVAULTING` (still in vault, still hidden? — see §11 open item O1).

## 7. UI / navigation

- **`HistoryViewModel`**: normal Library filters to `vaultState == PUBLIC` (so `VAULTING`/`VAULTED`/`UNVAULTING` are all absent from the normal list).
- **`VaultViewModel`**: filters to `vaultState == VAULTED` (in-progress moves are not shown as stable vault entries). Reuses the existing `VideoItem` model + card UI.
- **Navigation**: new `vault` route in the `NavHost`. The History screen gets a "Hidden vault" entry point that triggers the auth flow (§8) before navigating. Entry to `vault` is allowed only with live unlock state.
- **Playback**: `PlayerUriResolver` gains a branch — `if (vaultState == VAULTED) → content:// via FileProvider from vaultFilePath` (per-side aware). **Use FileProvider, not `file://`** (codex review: avoids `FileUriExposedException`/StrictMode if the URI crosses a process boundary; reuses the existing share FileProvider authority where possible). The vaulted player route is reachable only from inside the unlocked vault.
- **Screenshot/recents protection**: vault list + vault player screens set `FLAG_SECURE` to block screenshots and recents-thumbnail capture.

## 8. Authentication & locking

- **Dependency**: `androidx.biometric:biometric:1.1.0` (catalog + `app/build.gradle.kts`).
- **`VaultAuthGate`** seam: wraps `BiometricPrompt` with `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` — fingerprint/face with automatic phone pattern/PIN fallback; no custom password, no key storage. On **API 24–27** (below `BiometricPrompt`'s device-credential support) fall back to `KeyguardManager.createConfirmDeviceCredentialIntent()`. Pure decision logic (enrolled? which path? lock-state reducer) is extracted as a JVM-testable helper per the house pure-seam pattern; framework calls stay a thin wrapper.
- **What is gated**:
  1. Opening the vault — auth on every entry.
  2. Turning the toggle from **ON → OFF** — prompts auth (so an unlocked phone can't silently expose future recordings). ON and any other settings change are free.
- **Re-lock (defense in depth, codex review)**: in-memory `vaultUnlocked` flag, never persisted. Cleared on:
  - `ProcessLifecycleOwner` `ON_STOP` (app backgrounded; fires even though the recording foreground service keeps running, because no UI is resumed),
  - `Activity onStop` / `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)`,
  - leaving the `vault` route.
- **No credential enrolled** (`canAuthenticate → ERROR_NONE_ENROLLED`): gallery-hiding still works (storage is app-private regardless), but in-app lock cannot engage. Surface an explicit state: *"Set a device screen lock to protect the vault. Recordings are still hidden from the gallery, but the in-app lock is off."* Do **not** present the vault as protected when it isn't.

## 9. Move in / out (`VaultMover`)

In `service/export/` (touches publish + delete). Driven by actions on Library / Vault cards. **Every transition writes the manifest only after the destructive step verifies**, and uses the `VaultState` intermediates so a crash is always recoverable.

### 9.1 Move INTO vault (no auth — adding to the vault exposes nothing)

1. Ensure the merged file exists in app-private storage:
   - Tier1: copy bytes back from the MediaStore row,
   - Tier2/3: copy from `publicTargetPath`,
   - SAF: copy from the SAF doc.
2. Validate the private copy → manifest `VAULTING` with `vaultFilePath` set (now hidden from History, recoverable).
3. **Delete the public copy** — `resolver.delete(pendingUri)` (Tier1) / file delete **+ media rescan to drop the stale gallery index/thumbnail** (Tier2/3, codex review) / `DocumentFile.delete()` (SAF). All these artifacts were created by Rova.
4. On confirmed delete → manifest `VAULTED`, clear the public field.

### 9.2 Move OUT of vault (auth already passed inside the unlocked vault)

1. Manifest `UNVAULTING`.
2. Publish the **existing** vault file via a **publish-existing-file** path (copy bytes only — **no re-merge**, so `VideoMerger.mergeSegments` is not called and the single-entry gate is not tripped). Reuse the same publisher used by normal export so display-name / MIME / relative-path / pending semantics match a normally-exported file (codex review: avoids metadata drift). Destination = `currentExportTier(hasUsableSafFolder)` recomputed **now**, so it respects the user's current SAF config.
3. On confirmed publish → manifest `PUBLIC`, populate the matching public field, clear `vaultFilePath`. The private file becomes cleanup-eligible under the normal predicate.

## 10. ADR, static gate, testing

### ADR-0025 — "Private vault (gallery-hidden + biometric gate)"
Records the invariants: a vaulted export never enters MediaStore/public/SAF storage; `vaultState` is mutable while `exportTier` stays frozen; auth gates vault-view + toggle-off; no encryption in v1 (with the future-extension path); vault files/manifests are excluded from Auto Backup; vault UI uses `FLAG_SECURE`.

### Static gate (invariant → `check*` → `preBuild`, 29th gate)
**`checkVaultExporterNoPublicPublish`** — scans `VaultExporter` for any `MediaStore` / `insert` / `MediaScannerConnection` / public-path write and fails if present. Makes the core privacy guarantee mechanically enforced, not just convention. (Consider a companion check that vaulted sessions are not resolvable by any non-vault player/share route — see §11 O2.)

### Testing (pure-JVM only, per project policy)
- `SessionManifest` schema 6→7 round-trip + tolerant read of an old manifest (`vaultState` defaults `PUBLIC`).
- `VaultAuthGate` decision helper: enrolled / not-enrolled → path selection; lock-state reducer (unlock → `ON_STOP` → relocked → re-prompt).
- `VaultMover` transitions: `PUBLIC→VAULTING→VAULTED` and `VAULTED→UNVAULTING→PUBLIC`, field population, and the "manifest written only after destructive step" ordering, against a fake publish/delete seam. Include crash-at-each-intermediate recovery.
- Library vs Vault filter partition (`PUBLIC` vs `VAULTED`; intermediates absent from both stable lists).
- `ExportPipeline` routes a vault-intent session to the vault path (fake exporter seam).
- The new `check*` task verified against a known-bad fixture.

## 11. Backup, leakage, and open items

- **Auto Backup exclusion (codex review)**: add data-extraction / full-backup rules excluding the vault directory **and** the session manifests from cloud backup + device-transfer, so "private" videos don't sync off-device. (Confirm scope: exclude only vault artifacts, or all session manifests.)
- **No-leak audit**: ensure no deep link, notification tap, share-sheet target, recents entry, or legacy player route can resolve a `VAULTED` session without live unlock state. History filtering + the gated route + the player branch must all agree.
- **Share/export-out is a deliberate de-protection**: once shared/exported out, the copy is no longer gallery-hidden. UI should make that explicit.

### Resolved decisions (owner approved 2026-06-04 — defaults kept)
- **O1 → RESOLVED**: An interrupted move-out (`UNVAULTING`) keeps the recording **in the vault (hidden)** until `PUBLIC` is confirmed. The `VaultViewModel` therefore shows `VAULTED` **and** `UNVAULTING` (an in-flight move-out is still a vault item until publish verifies); the normal Library shows only `PUBLIC`. `VAULTING` is shown in neither stable list.
- **O2 → RESOLVED**: For v1, the single `checkVaultExporterNoPublicPublish` gate **plus** the no-leak audit + tests is sufficient. The second "no non-vault route resolves a vaulted session" gate is **deferred** (the audit + route tests cover it; revisit if the route surface grows).
- **O3 → RESOLVED**: Auto Backup exclusion covers the **vault video artifacts only** (the app-private vault files / directory). Session manifests are **not** excluded — excluding all manifests would break post-restore recovery for non-vault sessions, and the sensitive payload is the video, not the manifest. (Future encryption work, §12, can revisit manifest-level metadata exposure.)

## 12. Future extension — encryption at rest (not v1)

The `vaultState` + `vaultFilePath` model layers cleanly under encryption: encrypt bytes during the vault merge with an Android Keystore key gated by biometric, and decrypt-on-the-fly into Media3. No data-model reshape required — only the exporter, the player data source, and key management change.

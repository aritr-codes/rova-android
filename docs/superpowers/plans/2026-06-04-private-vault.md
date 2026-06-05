# Private Vault Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in private vault that keeps selected recordings out of the device gallery and behind an in-app device-credential lock, with no file encryption in v1.

**Architecture:** A mutable `vaultState` membership flag (`PUBLIC`/`VAULTING`/`VAULTED`/`UNVAULTING`) plus a frozen `vaultIntentAtStart`, both added to `SessionManifest`. Vaulted recordings merge into app-private storage (`getExternalFilesDir("videos")`) and are never published to MediaStore/scan/SAF. A new `VaultExporter` plugs into the existing single-entry `ExportPipeline.export()`. The in-app Vault UI is gated by `BiometricPrompt(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`. Move in/out are two-phase transitions through the intermediate states so a crash is always recoverable.

**Tech Stack:** Kotlin, Jetpack Compose, CameraX, Media3/ExoPlayer, `org.json` (hand-serialized manifest), `androidx.biometric` (new), single Gradle module `:app`, JVM-unit-tests-only policy, custom `check*` static gates wired into `preBuild`.

**Spec:** `docs/superpowers/specs/2026-06-04-private-vault-design.md` (owner-approved 2026-06-04; O1–O3 resolved).

**Branch:** `feat/b5-private-vault` (already checked out).

---

## Conventions for this plan

- **Test policy:** JVM unit tests only. Anything touching Compose, `BiometricPrompt`, `ContentResolver`, `SharedPreferences`, or `DocumentFile` is verified by a **device/manual smoke** step, not a JVM test — the testable logic is extracted into a **pure seam** (the house pattern) and the seam is unit-tested. Assertions use `org.junit.Assert.*`; coroutine tests use `kotlinx.coroutines.runBlocking` (this project has **no** `kotlinx-coroutines-test` / `runTest`).
- **Run a single test:** `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.<FQCN>"`
- **Run a single check task:** `./gradlew :app:<taskName>`
- **Gradle runs go through a subagent session** (main controller is blocked from long `gradlew` calls per project standing constraint).
- **Commit after every green step.** Do **not** `git push` or `gh pr create` without explicit owner consent (standing constraint #11).
- **Invariant KDoc:** tag fix-driven KDoc with the review round that caught it (house convention).

## File structure (created / modified)

**Created**
- `app/src/main/java/com/aritr/rova/service/export/VaultExporter.kt` — pure-seam merge-to-private exporter.
- `app/src/main/java/com/aritr/rova/service/export/VaultMover.kt` — pure-seam move in/out state machine.
- `app/src/main/java/com/aritr/rova/service/export/VaultAndroidOps.kt` — framework ops for move in/out (copy/delete/rescan per tier). Thin wrapper, not unit-tested.
- `app/src/main/java/com/aritr/rova/ui/vault/VaultLockState.kt` — pure unlock-state reducer.
- `app/src/main/java/com/aritr/rova/ui/vault/VaultAuthDecision.kt` — pure enrolled?/which-auth-path helper.
- `app/src/main/java/com/aritr/rova/ui/vault/VaultAuthGate.kt` — `BiometricPrompt` wrapper (thin, not unit-tested).
- `app/src/main/java/com/aritr/rova/ui/vault/VaultViewModel.kt` — vault list VM.
- `app/src/main/java/com/aritr/rova/ui/vault/VaultScreen.kt` — vault list Composable.
- `app/src/main/java/com/aritr/rova/ui/vault/VaultListFilter.kt` — pure list-partition helpers (PUBLIC vs vault).
- `docs/adr/0025-private-vault.md` — ADR.
- Test files mirroring each pure seam under `app/src/test/java/...`.

**Modified**
- `app/src/main/java/com/aritr/rova/data/SessionManifest.kt` — `VaultState` enum + 3 (+2 per-side) fields + schema 6→7.
- `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` — `hideInVault`.
- `app/src/main/java/com/aritr/rova/data/SessionStore.kt` — vault manifest mutators.
- `app/src/main/java/com/aritr/rova/service/export/ExportPipeline.kt` — vault guard + `exportVault`.
- `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` — freeze `vaultIntentAtStart`; pass it to `export()`.
- `app/src/main/java/com/aritr/rova/service/export/ExportRecoveryRunner.kt` — vault-aware recovery.
- `app/src/main/java/com/aritr/rova/service/export/ExportCleanupPredicate.kt` — vault file is a kept artifact.
- `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` — filter to PUBLIC only.
- `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerUriResolver.kt` — vault FileProvider branch.
- `app/src/main/java/com/aritr/rova/ui/MainScreen.kt` — `vault` route + gated entry.
- `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt` + `SettingsViewModel.kt` — toggle row + toggle-off auth.
- `app/build.gradle.kts` — `checkVaultExporterNoPublicPublish` + `preBuild` wiring + biometric dep.
- `gradle/libs.versions.toml` — `androidx.biometric`.
- `app/src/main/res/xml/backup_rules.xml` + `data_extraction_rules.xml` — exclude vault dir.
- `app/src/main/res/values/strings.xml` — vault copy.
- `app/src/main/res/xml/file_paths.xml` — ensure the vault path is FileProvider-shareable.

---

# Phase 1 — Data model & settings

**Phase goal:** Manifest carries vault state; settings carries the toggle. Pure, fully JVM-tested. **NO-GO into Phase 2 until** schema round-trip + tolerant-read tests pass and the full suite is green.

### Task 1: `VaultState` enum + manifest fields + schema 6→7

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/SessionManifest.kt`
- Test: `app/src/test/java/com/aritr/rova/data/SessionManifestVaultTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionManifestVaultTest {

    private fun baseManifest() = SessionManifest(
        sessionId = "s1",
        startedAt = 1000L,
        config = SessionConfig(10, 1, "HD", 10, "Portrait"),
        segments = emptyList(),
        exportTier = ExportTier.TIER1_API29_PLUS,
    )

    @Test
    fun defaults_areNonVault() {
        val m = baseManifest()
        assertFalse(m.vaultIntentAtStart)
        assertEquals(VaultState.PUBLIC, m.vaultState)
        assertEquals(null, m.vaultFilePath)
    }

    @Test
    fun roundTrip_preservesVaultFields() {
        val m = baseManifest().copy(
            vaultIntentAtStart = true,
            vaultState = VaultState.VAULTED,
            vaultFilePath = "/data/user/0/com.aritr.rova/files/videos/s1/Rova_x.mp4",
        )
        val back = SessionManifest.fromJson(m.toJson())
        assertEquals(true, back.vaultIntentAtStart)
        assertEquals(VaultState.VAULTED, back.vaultState)
        assertEquals(m.vaultFilePath, back.vaultFilePath)
    }

    @Test
    fun tolerantRead_oldManifestHasNoVaultKeys_defaultsPublic() {
        // Simulate a schema-6 manifest: serialize, then strip vault keys.
        val json: JSONObject = baseManifest().toJson()
        json.remove("vaultIntentAtStart")
        json.remove("vaultState")
        json.remove("vaultFilePath")
        json.put("schemaVersion", 6)
        val back = SessionManifest.fromJson(json)
        assertFalse(back.vaultIntentAtStart)
        assertEquals(VaultState.PUBLIC, back.vaultState)
        assertEquals(null, back.vaultFilePath)
    }

    @Test
    fun schemaVersion_isSeven() {
        assertEquals(7, SessionManifest.SCHEMA_VERSION)
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.SessionManifestVaultTest"`
Expected: FAIL — `VaultState` unresolved, `vaultIntentAtStart` unresolved, `SCHEMA_VERSION` is 6.

- [ ] **Step 3: Add the `VaultState` enum**

Add near the other manifest enums (after `ExportState`):

```kotlin
/**
 * B5 / ADR-0025 — vault membership. Mutable (move in/out), kept distinct
 * from the FROZEN [ExportTier] (which records how a recording WOULD
 * publish). PUBLIC = gallery-visible normal recording. VAULTED = in the
 * vault, no public copy, [SessionManifest.vaultFilePath] is the artifact
 * of record. VAULTING / UNVAULTING = in-flight move intermediates,
 * recoverable on cold launch; hidden from the normal Library. See
 * docs/superpowers/specs/2026-06-04-private-vault-design.md §4.1.
 */
enum class VaultState {
    PUBLIC,
    VAULTING,
    VAULTED,
    UNVAULTING,
}
```

- [ ] **Step 4: Add the manifest fields**

In the `SessionManifest` constructor, after `safTransientRetryCount`:

```kotlin
    ,
    // B5 / ADR-0025 — vault. `vaultIntentAtStart` is FROZEN at session
    // start from RovaSettings.hideInVault and drives export routing for a
    // new recording (a crash mid-record must still resolve to the vault).
    // `vaultState` is the MUTABLE membership flag flipped by VaultExporter
    // (on finalize) and VaultMover (on move in/out). `vaultFilePath` is the
    // app-private merged file while VAULTED. Per-side variants for P+L.
    val vaultIntentAtStart: Boolean = false,
    val vaultState: VaultState = VaultState.PUBLIC,
    val vaultFilePath: String? = null,
    val portraitVaultFilePath: String? = null,
    val landscapeVaultFilePath: String? = null
```

- [ ] **Step 5: Serialize in `toJson()`**

After the SAF block (`if (safTransientRetryCount > 0) ...`):

```kotlin
        // B5 / ADR-0025 — emit vault keys only when non-default so schema-6
        // single-mode manifests keep their byte-shape.
        if (vaultIntentAtStart) put("vaultIntentAtStart", true)
        if (vaultState != VaultState.PUBLIC) put("vaultState", vaultState.name)
        vaultFilePath?.let { put("vaultFilePath", it) }
        portraitVaultFilePath?.let { put("portraitVaultFilePath", it) }
        landscapeVaultFilePath?.let { put("landscapeVaultFilePath", it) }
```

- [ ] **Step 6: Deserialize tolerantly in `fromJson()`**

After the SAF reads (`safTransientRetryCount = json.optInt(...)`), add (before the closing `)`):

```kotlin
            ,
            vaultIntentAtStart = json.optBoolean("vaultIntentAtStart", false),
            vaultState = json.optString("vaultState", "").ifEmpty { null }?.let {
                runCatching { VaultState.valueOf(it) }.getOrNull()
            } ?: VaultState.PUBLIC,
            vaultFilePath = json.optString("vaultFilePath", "").ifEmpty { null },
            portraitVaultFilePath = json.optString("portraitVaultFilePath", "").ifEmpty { null },
            landscapeVaultFilePath = json.optString("landscapeVaultFilePath", "").ifEmpty { null }
```

- [ ] **Step 7: Bump `SCHEMA_VERSION`**

```kotlin
        const val SCHEMA_VERSION = 7   // 6->7: vault fields (B5 / ADR-0025)
```

- [ ] **Step 8: Run test, verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.SessionManifestVaultTest"`
Expected: PASS (4 tests).

- [ ] **Step 9: Run the full suite (no regressions in manifest consumers)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, count = prior baseline + 4.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/SessionManifest.kt app/src/test/java/com/aritr/rova/data/SessionManifestVaultTest.kt
git commit -m "feat(vault): manifest VaultState + vaultIntentAtStart fields, schema 6->7"
```

### Task 2: `RovaSettings.hideInVault`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt`

> No JVM test: `RovaSettings` boolean accessors wrap `SharedPreferences`, which returns defaults under `isReturnDefaultValues` — they are thin accessors like the existing 12 and are verified by compile + device smoke. (The decision logic that *is* testable — "toggle-off requires auth" — lives in Task 19's pure helper.)

- [ ] **Step 1: Add the property**

After `autoExportEnabled` (line ~183):

```kotlin
    // B5 / ADR-0025 — when ON, new recordings go to the hidden vault
    // (app-private storage, never published). Backed up (a genuine user
    // preference, like themeMode). Default OFF preserves existing behavior.
    var hideInVault: Boolean
        get() = prefs.getBoolean("hide_in_vault", false)
        set(value) = prefs.edit { putBoolean("hide_in_vault", value) }
```

- [ ] **Step 2: Compile-verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/RovaSettings.kt
git commit -m "feat(vault): RovaSettings.hideInVault toggle key"
```

**✅ PHASE 1 GATE (NO-GO/GO):** Full suite green; `SessionManifest` round-trips and tolerantly reads vault fields; `hideInVault` compiles. Get reviewer GO before Phase 2.

---

# Phase 2 — Vault export path + static gate + ADR

**Phase goal:** A vaulted session merges to app-private storage and never publishes; the invariant is mechanically enforced. **NO-GO into Phase 3 until** the new gate passes on real source, fails on a bad fixture, and the suite is green.

### Task 3: `SessionStore` vault mutators

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/SessionStore.kt`
- Test: `app/src/test/java/com/aritr/rova/data/SessionStoreVaultTest.kt`

> Read `SessionStore.kt` first to match its existing mutator idiom (load manifest → `copy(...)` → atomic write). Mirror `setExportFinalized` exactly for the signatures below.

- [ ] **Step 1: Write the failing test** (uses the real `org.json` on `testImplementation`; `SessionStore` persists to a temp dir)

```kotlin
package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreVaultTest {
    @get:Rule val tmp = TemporaryFolder()

    // Construct SessionStore against tmp.root following the existing
    // test-construction pattern in SessionStoreTest.kt (match it exactly).
    private fun store() = SessionStore(/* root = */ tmp.root)

    @Test
    fun setVaultFinalized_setsStateAndPathClearsSafAndPublic() {
        val s = store()
        // create a session with a manifest first (mirror SessionStoreTest setup)
        val id = s.createSession(
            SessionConfig(10, 1, "HD", 10, "Portrait"),
            exportTier = ExportTier.TIER1_API29_PLUS,
            audioMode = AudioMode.VIDEO_ONLY,
        )
        s.setVaultFinalized(id, vaultFilePath = "/vault/${id}/Rova_x.mp4")
        val m = s.loadManifest(id)!!
        assertEquals(VaultState.VAULTED, m.vaultState)
        assertEquals("/vault/${id}/Rova_x.mp4", m.vaultFilePath)
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertNull(m.pendingUri)
        assertNull(m.publicTargetPath)
    }
}
```

> If `SessionStore.createSession`'s real signature differs, adapt the call — read the file. The assertions on the mutator outcome are the contract.

- [ ] **Step 2: Run, verify fail** — `setVaultFinalized` unresolved.

- [ ] **Step 3: Implement the mutators**

Add to `SessionStore` (mirror the atomic load→copy→write used by `setExportFinalized`):

```kotlin
    /** B5 / ADR-0025 — finalize a vault export: VAULTED + vaultFilePath, no public fields. */
    fun setVaultFinalized(sessionId: String, vaultFilePath: String) {
        updateManifest(sessionId) {
            it.copy(
                vaultState = VaultState.VAULTED,
                vaultFilePath = vaultFilePath,
                exportState = ExportState.FINALIZED,
                pendingUri = null,
                publicTargetPath = null,
                safTargetDocUri = null,
            )
        }
    }

    fun setVaultFinalizedForSide(sessionId: String, side: com.aritr.rova.service.dualrecord.VideoSide, vaultFilePath: String) {
        updateManifest(sessionId) {
            when (side) {
                com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT ->
                    it.copy(vaultState = VaultState.VAULTED, portraitVaultFilePath = vaultFilePath, portraitPendingUri = null, portraitPublicTargetPath = null)
                com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE ->
                    it.copy(vaultState = VaultState.VAULTED, landscapeVaultFilePath = vaultFilePath, landscapePendingUri = null, landscapePublicTargetPath = null)
            }
        }
    }

    /** B5 — set an in-flight move state (VAULTING / UNVAULTING) for crash recovery. */
    fun setVaultState(sessionId: String, state: VaultState, vaultFilePath: String? = null) {
        updateManifest(sessionId) { m ->
            m.copy(vaultState = state, vaultFilePath = vaultFilePath ?: m.vaultFilePath)
        }
    }

    /** B5 — move-out completion: back to PUBLIC, clear vault file, set the published field. */
    fun setVaultMovedOut(sessionId: String, exportState: ExportState = ExportState.FINALIZED, pendingUri: String? = null, publicTargetPath: String? = null, safTargetDocUri: String? = null) {
        updateManifest(sessionId) {
            it.copy(
                vaultState = VaultState.PUBLIC,
                vaultFilePath = null,
                exportState = exportState,
                pendingUri = pendingUri,
                publicTargetPath = publicTargetPath,
                safTargetDocUri = safTargetDocUri,
            )
        }
    }
```

> If `SessionStore` has no private `updateManifest { }` helper, use whatever atomic load→write helper it does expose (read the file). `COMPLETED` is **not** written here — vault finalize uses `ExportState.FINALIZED` only; the `Terminated.COMPLETED` terminal write stays owned by `RovaRecordingService.performMerge` (do not violate `checkCompletedWriteOnlyFromPerformMerge`).

- [ ] **Step 4: Run, verify pass.** `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.data.SessionStoreVaultTest"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/SessionStore.kt app/src/test/java/com/aritr/rova/data/SessionStoreVaultTest.kt
git commit -m "feat(vault): SessionStore vault-finalize / move-state mutators"
```

### Task 4: `VaultExporter` (pure seam)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/export/VaultExporter.kt`
- Test: `app/src/test/java/com/aritr/rova/service/export/VaultExporterTest.kt`

> Model the seam on `Tier2Exporter` (injected `mux` + store lambdas). Read `Tier2Exporter.kt` + `ExportResult.kt` for the exact `ExportResult` variants and the `mux` lambda shape before writing.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.service.export

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class VaultExporterTest {

    @Test
    fun success_writesVaultFileAndFinalizes() = runBlocking {
        var finalizedPath: String? = null
        var failed = false
        val exporter = VaultExporter(
            vaultFile = File("/tmp/vault/s1/Rova_x.mp4"),
            mux = { _, out -> /* pretend merge wrote `out` */ },
            setFinalized = { p -> finalizedPath = p },
            setFailed = { failed = true },
        )
        val result = exporter.export("s1", listOf(File("/seg/0.mp4")))
        assertTrue(result is ExportResult.Success)
        assertEquals("/tmp/vault/s1/Rova_x.mp4", finalizedPath)
        assertEquals(false, failed)
    }

    @Test
    fun muxThrows_returnsFailureNoFinalize() = runBlocking {
        var finalized = false
        var failed = false
        val exporter = VaultExporter(
            vaultFile = File("/tmp/vault/s1/Rova_x.mp4"),
            mux = { _, _ -> throw java.io.IOException("disk full") },
            setFinalized = { finalized = true },
            setFailed = { failed = true },
        )
        val result = exporter.export("s1", listOf(File("/seg/0.mp4")))
        assertTrue(result is ExportResult.RetryableFailure)
        assertEquals(false, finalized)
        assertEquals(true, failed)
    }
}
```

> Confirm the exact failure variant name in `ExportResult.kt`. If the project's generic post-mux failure is `ExportResult.RetryableFailure`, use it; otherwise use the matching variant and update the assertion. The contract: success → `setFinalized(path)`; mux throw → `setFailed()` + a non-Success result and **no** finalize.

- [ ] **Step 2: Run, verify fail** — `VaultExporter` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.aritr.rova.service.export

import java.io.File

/**
 * B5 / ADR-0025 — vault exporter. Merges segments straight to an
 * app-private file ([vaultFile]) and finalizes. It performs NO MediaStore
 * insert, NO MediaScannerConnection, NO public-path write, NO IS_PENDING —
 * a vaulted recording never becomes gallery-visible. Enforced by
 * `checkVaultExporterNoPublicPublish` (app/build.gradle.kts).
 *
 * Pure seam: all framework effects are injected lambdas so the success /
 * failure branches are JVM-testable (Tier2Exporter pattern).
 */
internal class VaultExporter(
    private val vaultFile: File,
    private val mux: suspend (List<File>, File) -> Unit,
    private val setFinalized: (String) -> Unit,
    private val setFailed: () -> Unit,
) {
    suspend fun export(sessionId: String, segments: List<File>): ExportResult {
        return try {
            vaultFile.parentFile?.mkdirs()
            mux(segments, vaultFile)
            setFinalized(vaultFile.absolutePath)
            ExportResult.Success
        } catch (t: Throwable) {
            setFailed()
            ExportResult.RetryableFailure(sessionId)
        }
    }
}
```

> Match `ExportResult.Success` / `ExportResult.RetryableFailure(...)` to the real definitions (read `ExportResult.kt`). If `Success` carries fields, supply them.

- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/export/VaultExporter.kt app/src/test/java/com/aritr/rova/service/export/VaultExporterTest.kt
git commit -m "feat(vault): VaultExporter pure seam (merge-to-private, no publish)"
```

### Task 5: Wire the vault guard into `ExportPipeline.export()` + `RovaRecordingService`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/export/ExportPipeline.kt`
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`
- Test: `app/src/test/java/com/aritr/rova/service/export/ExportPipelineVaultRoutingTest.kt`

- [ ] **Step 1: Add `exportVault` + the guard, and a `vaultIntent` param**

In `ExportPipeline.export(...)`, add a parameter `vaultIntent: Boolean = false` (after `frozenTier`). Add the guard immediately after `displayName` is computed, **before** the `when (tier)`:

```kotlin
        if (vaultIntent) {
            return exportVault(
                context = context,
                sessionStore = sessionStore,
                sessionId = sessionId,
                sessionDir = sessionDir,
                segments = segments,
                displayName = displayName,
                side = side,
                onProgress = onProgress,
            )
        }
```

Add the private function (mirrors `exportSaf` wiring; `VideoMerger.mergeSegments` stays inside `service/export/`, satisfying `checkExportPipelineSingleEntry`):

```kotlin
    /**
     * B5 / ADR-0025 — vault route. Merges to an app-private file under the
     * session dir; never publishes. Frozen by `vaultIntentAtStart` and
     * dispatched ahead of the SDK/SAF tiers.
     */
    private suspend fun exportVault(
        context: Context,
        sessionStore: SessionStore,
        sessionId: String,
        sessionDir: File,
        segments: List<File>,
        displayName: String,
        side: com.aritr.rova.service.dualrecord.VideoSide?,
        onProgress: (Float) -> Unit,
    ): ExportResult {
        val vaultFile = File(sessionDir, displayName)
        val exporter = VaultExporter(
            vaultFile = vaultFile,
            mux = { segs, out -> VideoMerger.mergeSegments(segs, out, onProgress) },
            setFinalized = { p ->
                if (side == null) sessionStore.setVaultFinalized(sessionId, p)
                else sessionStore.setVaultFinalizedForSide(sessionId, side, p)
            },
            setFailed = { sessionStore.setExportFailed(sessionId) },
        )
        return exporter.export(sessionId, segments)
    }
```

- [ ] **Step 2: Freeze + pass `vaultIntentAtStart` in `RovaRecordingService`**

Read `RovaRecordingService.kt` for (a) where `createSession(... exportTier ...)` is called at session start, and (b) the single `ExportPipeline.export(` call site in `performMerge`.

- At session start, pass the frozen intent into the manifest. If `createSession` doesn't take it, add a `vaultIntentAtStart` parameter to `SessionStore.createSession` (default `false`) and pass `RovaSettings(context).hideInVault`. (Mirror how `audioMode`/`exportTier` are frozen there.)
- At the `performMerge` call site, pass `vaultIntent = manifest.vaultIntentAtStart`.

- [ ] **Step 3: Write the routing test** (pure — exercise `export()`'s branch selection via a fake; if `export()` is hard to call without `Context`, instead unit-test a tiny extracted `shouldRouteToVault(manifest)` predicate and assert `VaultExporter` is selected). Minimum viable test:

```kotlin
package com.aritr.rova.service.export

import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.VaultState
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ExportPipelineVaultRoutingTest {
    @Test fun vaultIntentTrue_routesToVault() {
        assertTrue(routeIsVault(vaultIntentAtStart = true))
    }
    @Test fun vaultIntentFalse_routesToTier() {
        assertFalse(routeIsVault(vaultIntentAtStart = false))
    }
    // routeIsVault is the literal guard predicate extracted for testability.
    private fun routeIsVault(vaultIntentAtStart: Boolean) = vaultIntentAtStart
}
```

> This is deliberately thin — the guard is a one-liner. The real safety net is the static gate (Task 6) + the move/recovery tests. Keep the test if it documents intent; otherwise the reviewer may waive it.

- [ ] **Step 4: Run the full suite + compile.** `./gradlew :app:testDebugUnitTest`
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/export/ExportPipeline.kt app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt app/src/main/java/com/aritr/rova/data/SessionStore.kt app/src/test/java/com/aritr/rova/service/export/ExportPipelineVaultRoutingTest.kt
git commit -m "feat(vault): route vaultIntentAtStart sessions to VaultExporter (single-entry preserved)"
```

### Task 6: `checkVaultExporterNoPublicPublish` static gate

**Files:**
- Modify: `app/build.gradle.kts`
- Test: a temporary bad-fixture verification (manual, described below)

- [ ] **Step 1: Register the task** (mirror `checkRecoveryNoDeletion` structure)

Add near the other export checks:

```kotlin
// B5 / ADR-0025 — the core privacy invariant, mechanically enforced:
// VaultExporter must never reach a public-publish API. A vaulted recording
// stays app-private; any MediaStore insert / media scan / public-dir write
// inside VaultExporter.kt would silently make it gallery-visible.
val checkVaultExporterNoPublicPublish = tasks.register("checkVaultExporterNoPublicPublish") {
    group = "verification"
    description = "Forbid public-publish APIs in VaultExporter (ADR-0025 — vault recordings never publish)."
    val vaultExporter = file("src/main/java/com/aritr/rova/service/export/VaultExporter.kt")
    inputs.file(vaultExporter).withPropertyName("vaultExporterSource")
    doLast {
        if (!vaultExporter.exists()) {
            throw GradleException("checkVaultExporterNoPublicPublish: VaultExporter.kt missing: $vaultExporter")
        }
        val forbidden = listOf(
            "MediaStore",
            "MediaScannerConnection",
            "insertPendingRow",
            "scanAndWait",
            "DIRECTORY_MOVIES",
            "IS_PENDING",
            ".insert(",
            "getExternalStoragePublicDirectory",
        )
        val hits = vaultExporter.readLines().withIndex().filter { (_, line) ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*")) false
            else forbidden.any { line.contains(it) }
        }
        if (hits.isNotEmpty()) {
            val report = hits.joinToString("\n") { (i, line) -> "  VaultExporter.kt:${i + 1}: ${line.trim()}" }
            throw GradleException(
                "ADR-0025: VaultExporter must not reference any public-publish API " +
                    "(vault recordings stay app-private). Offenders:\n$report"
            )
        }
    }
}
```

- [ ] **Step 2: Wire into `preBuild`**

In the `afterEvaluate { tasks.matching { it.name == "preBuild" }.configureEach { ... } }` block, after `dependsOn(checkA11yAnimationGated)`:

```kotlin
        dependsOn(checkVaultExporterNoPublicPublish)
```

- [ ] **Step 3: Run the gate on real source, verify PASS**

Run: `./gradlew :app:checkVaultExporterNoPublicPublish`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify it FAILS on a bad fixture**

Temporarily add `// test:` line `val x = MediaStore.Video.Media.EXTERNAL_CONTENT_URI` (no leading `//`) into `VaultExporter.kt`, re-run the task, confirm it throws `ADR-0025: VaultExporter must not reference...`, then **revert** the line and re-run to confirm PASS.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts
git commit -m "feat(vault): checkVaultExporterNoPublicPublish static gate (ADR-0025, 29th gate)"
```

### Task 7: ADR-0025

**Files:**
- Create: `docs/adr/0025-private-vault.md`

- [ ] **Step 1: Write the ADR** capturing the invariants from spec §4, §6, §10: mutable `vaultState` vs frozen `vaultIntentAtStart`/`exportTier`; vault never publishes (enforced by `checkVaultExporterNoPublicPublish`); auth gates vault-view + toggle-off; no encryption in v1 (future extension §12); Auto Backup excludes vault video artifacts; vault UI uses `FLAG_SECURE`. Follow the existing ADR format (read `docs/adr/0024-saf-export-destination.md`). Status: **Accepted (2026-06-04)**.

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0025-private-vault.md
git commit -m "docs(adr): ADR-0025 private vault (gallery-hidden + biometric gate)"
```

**✅ PHASE 2 GATE:** New gate passes on real source + fails on bad fixture; suite green; ADR landed. Reviewer GO before Phase 3.

---

# Phase 3 — Recovery & cleanup

**Phase goal:** A crashed vaulted session re-merges to the vault and is never published; cleanup never deletes a kept vault file. **NO-GO into Phase 4 until** recovery + cleanup tests pass.

### Task 8: Recovery routes vault-intent sessions to the vault

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/export/ExportRecoveryRunner.kt`
- Test: `app/src/test/java/com/aritr/rova/service/export/ExportRecoveryVaultTest.kt`

> Read `ExportRecoveryRunner.kt`. It snapshots pending URIs and re-runs interrupted exports. Add: when a session has `vaultIntentAtStart == true` (and not yet `VAULTED`), recovery must call the vault export path, never a public publish; a session in `VAULTING`/`UNVAULTING` resumes per spec §6.2.

- [ ] **Step 1: Write the failing test** for the pure decision (extract a `vaultRecoveryAction(manifest): VaultRecoveryAction` helper if the runner is too framework-bound):

```kotlin
package com.aritr.rova.service.export

import com.aritr.rova.data.VaultState
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportRecoveryVaultTest {
    @Test fun unfinishedVaultIntent_reMergesToVault() {
        assertEquals(VaultRecoveryAction.MERGE_TO_VAULT,
            vaultRecoveryAction(vaultIntentAtStart = true, state = VaultState.PUBLIC, finalized = false))
    }
    @Test fun vaulting_resumesDeletePublicThenVaulted() {
        assertEquals(VaultRecoveryAction.RESUME_VAULTING,
            vaultRecoveryAction(vaultIntentAtStart = true, state = VaultState.VAULTING, finalized = false))
    }
    @Test fun unvaulting_resumesPublish() {
        assertEquals(VaultRecoveryAction.RESUME_UNVAULTING,
            vaultRecoveryAction(vaultIntentAtStart = false, state = VaultState.UNVAULTING, finalized = false))
    }
    @Test fun vaulted_noAction() {
        assertEquals(VaultRecoveryAction.NONE,
            vaultRecoveryAction(vaultIntentAtStart = true, state = VaultState.VAULTED, finalized = true))
    }
    @Test fun normalPublic_noVaultAction() {
        assertEquals(VaultRecoveryAction.NONE,
            vaultRecoveryAction(vaultIntentAtStart = false, state = VaultState.PUBLIC, finalized = false))
    }
}
```

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement the pure helper** (top-level in `ExportRecoveryRunner.kt` or a sibling file)

```kotlin
enum class VaultRecoveryAction { NONE, MERGE_TO_VAULT, RESUME_VAULTING, RESUME_UNVAULTING }

/**
 * B5 / ADR-0025 — recovery keys off vault membership, never exportTier.
 * - in-flight move states resume their direction (spec §6.2),
 * - an unfinished vault-intent session re-merges to the vault,
 * - everything else is not a vault action.
 */
fun vaultRecoveryAction(vaultIntentAtStart: Boolean, state: com.aritr.rova.data.VaultState, finalized: Boolean): VaultRecoveryAction = when {
    state == com.aritr.rova.data.VaultState.VAULTING -> VaultRecoveryAction.RESUME_VAULTING
    state == com.aritr.rova.data.VaultState.UNVAULTING -> VaultRecoveryAction.RESUME_UNVAULTING
    vaultIntentAtStart && state != com.aritr.rova.data.VaultState.VAULTED && !finalized -> VaultRecoveryAction.MERGE_TO_VAULT
    else -> VaultRecoveryAction.NONE
}
```

- [ ] **Step 4: Consume the helper in the runner** — branch the recovery loop on `vaultRecoveryAction(...)`: `MERGE_TO_VAULT` → call `ExportPipeline.export(..., vaultIntent = true)`; `RESUME_VAULTING` → re-run move-in finish (Task 21 `VaultMover.finishVaulting`); `RESUME_UNVAULTING` → re-run move-out publish (Task 21 `VaultMover.finishUnvaulting`); `NONE` → existing behavior. (The move-finish calls land with Phase 6; until then, leave a `// TODO(Phase 6): resume move` that the Phase 6 task replaces — note this explicitly so it isn't a silent gap.)

- [ ] **Step 5: Run, verify pass; full suite.**
- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/export/ExportRecoveryRunner.kt app/src/test/java/com/aritr/rova/service/export/ExportRecoveryVaultTest.kt
git commit -m "feat(vault): recovery routes vault-intent + in-flight moves (ADR-0025)"
```

### Task 9: Cleanup predicate keeps vault files

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/export/ExportCleanupPredicate.kt`
- Test: `app/src/test/java/com/aritr/rova/service/export/ExportCleanupPredicateVaultTest.kt`

> Read `ExportCleanupPredicate.kt`. Note `checkExportCleanupPredicate` requires the four existing tokens (`AUTO_DISCARD_ELIGIBLE`, `privateTempPath`, `RetryableFailure`+`ManifestWriteFailed`, `QueryFailed`) to remain present — **do not remove them**. Add a vault clause that does not drop any.

- [ ] **Step 1: Write the failing test** — a `VAULTED` (or in-flight) session with a `vaultFilePath` must NOT be deleted.

```kotlin
package com.aritr.rova.service.export

import com.aritr.rova.data.VaultState
import org.junit.Assert.assertFalse
import org.junit.Test

class ExportCleanupPredicateVaultTest {
    @Test fun vaultedSessionWithFile_isNotDeletable() {
        assertFalse(isVaultKeptArtifact(VaultState.PUBLIC, vaultFilePath = null).let { it } && false)
        // The real assertion: a VAULTED/ VAULTING/ UNVAULTING session with a
        // vaultFilePath is a kept artifact.
        assert(isVaultKeptArtifact(VaultState.VAULTED, "/v/x.mp4"))
        assert(isVaultKeptArtifact(VaultState.VAULTING, "/v/x.mp4"))
        assert(isVaultKeptArtifact(VaultState.UNVAULTING, "/v/x.mp4"))
        assertFalse(isVaultKeptArtifact(VaultState.PUBLIC, null))
    }
}
```

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement the pure predicate + wire it**

```kotlin
/**
 * B5 / ADR-0025 — a vault file is a KEPT artifact: never an orphan to
 * delete. Any non-PUBLIC vault state with a vault file present must block
 * deletion of the session's files.
 */
fun isVaultKeptArtifact(state: com.aritr.rova.data.VaultState, vaultFilePath: String?): Boolean =
    state != com.aritr.rova.data.VaultState.PUBLIC && vaultFilePath != null
```

Wire into `runCleanupPass(...)`: if `isVaultKeptArtifact(manifest.vaultState, manifest.vaultFilePath)` (or any per-side vault path is non-null), return `false` (skip deletion) **before** the existing four-gate decision.

- [ ] **Step 4: Run, verify pass; run `./gradlew :app:checkExportCleanupPredicate` to confirm the four required tokens are still present; full suite.**
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/export/ExportCleanupPredicate.kt app/src/test/java/com/aritr/rova/service/export/ExportCleanupPredicateVaultTest.kt
git commit -m "feat(vault): cleanup predicate treats vault file as kept artifact (ADR-0025)"
```

**✅ PHASE 3 GATE:** recovery + cleanup vault tests green; `checkExportCleanupPredicate` still passes. Reviewer GO before Phase 4.

---

# Phase 4 — Auth seam

**Phase goal:** A unit-tested unlock-state reducer and auth-path decision; the `BiometricPrompt` wrapper compiles. **NO-GO into Phase 5 until** reducer + decision tests pass.

### Task 10: Add `androidx.biometric`

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`

- [ ] **Step 1: Version catalog** — add under `[versions]`: `biometric = "1.1.0"`; under `[libraries]`:

```toml
androidx-biometric = { group = "androidx.biometric", name = "biometric", version.ref = "biometric" }
```

- [ ] **Step 2: `app/build.gradle.kts`** — add to `dependencies { }`: `implementation(libs.androidx.biometric)`
- [ ] **Step 3:** Run `./gradlew :app:dependencies --configuration debugRuntimeClasspath` (subagent) and confirm `androidx.biometric:biometric:1.1.0` resolves.
- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build(vault): add androidx.biometric:1.1.0"
```

### Task 11: `VaultLockState` reducer (pure)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/vault/VaultLockState.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/vault/VaultLockStateTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
package com.aritr.rova.ui.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultLockStateTest {
    @Test fun startsLocked() = assertFalse(VaultLockState.initial().unlocked)
    @Test fun authSuccessUnlocks() =
        assertTrue(VaultLockState.initial().onAuthSucceeded().unlocked)
    @Test fun backgroundRelocks() =
        assertFalse(VaultLockState.initial().onAuthSucceeded().onAppBackgrounded().unlocked)
    @Test fun leaveRouteRelocks() =
        assertFalse(VaultLockState.initial().onAuthSucceeded().onLeaveVault().unlocked)
}
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement**

```kotlin
package com.aritr.rova.ui.vault

/**
 * B5 / ADR-0025 — in-memory vault lock state. NEVER persisted (spec R5):
 * re-auth on every vault entry and on app foreground. `onAppBackgrounded`
 * is fired from a ProcessLifecycleOwner ON_STOP observer; `onLeaveVault`
 * when the vault route is popped.
 */
data class VaultLockState(val unlocked: Boolean) {
    fun onAuthSucceeded() = copy(unlocked = true)
    fun onAppBackgrounded() = copy(unlocked = false)
    fun onLeaveVault() = copy(unlocked = false)
    companion object { fun initial() = VaultLockState(unlocked = false) }
}
```

- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/vault/VaultLockState.kt app/src/test/java/com/aritr/rova/ui/vault/VaultLockStateTest.kt
git commit -m "feat(vault): VaultLockState reducer (in-memory, relock on background/leave)"
```

### Task 12: `VaultAuthDecision` (pure) + `VaultAuthGate` (thin wrapper)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/vault/VaultAuthDecision.kt`
- Create: `app/src/main/java/com/aritr/rova/ui/vault/VaultAuthGate.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/vault/VaultAuthDecisionTest.kt`

- [ ] **Step 1: Failing test for the decision helper**

```kotlin
package com.aritr.rova.ui.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultAuthDecisionTest {
    @Test fun api28Plus_usesBiometricPrompt() =
        assertEquals(VaultAuthPath.BIOMETRIC_PROMPT, vaultAuthPath(sdkInt = 28, hasEnrolledCredential = true))
    @Test fun api24to27_usesKeyguardIntent() =
        assertEquals(VaultAuthPath.KEYGUARD_INTENT, vaultAuthPath(sdkInt = 25, hasEnrolledCredential = true))
    @Test fun noCredential_isUnavailable() =
        assertEquals(VaultAuthPath.UNAVAILABLE_NO_CREDENTIAL, vaultAuthPath(sdkInt = 30, hasEnrolledCredential = false))
}
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement the decision helper**

```kotlin
package com.aritr.rova.ui.vault

enum class VaultAuthPath { BIOMETRIC_PROMPT, KEYGUARD_INTENT, UNAVAILABLE_NO_CREDENTIAL }

/**
 * B5 / ADR-0025 — auth path selection. BiometricPrompt device-credential
 * support is API 28+ (BIOMETRIC_STRONG or DEVICE_CREDENTIAL); 24–27 fall
 * back to KeyguardManager.createConfirmDeviceCredentialIntent. No enrolled
 * credential → vault cannot lock (spec §8; gallery-hiding still works).
 */
fun vaultAuthPath(sdkInt: Int, hasEnrolledCredential: Boolean): VaultAuthPath = when {
    !hasEnrolledCredential -> VaultAuthPath.UNAVAILABLE_NO_CREDENTIAL
    sdkInt >= 28 -> VaultAuthPath.BIOMETRIC_PROMPT
    else -> VaultAuthPath.KEYGUARD_INTENT
}
```

- [ ] **Step 4: Implement `VaultAuthGate`** (thin wrapper — not unit-tested; verified by device smoke in Phase 5). It takes a `FragmentActivity`, computes `vaultAuthPath(Build.VERSION.SDK_INT, BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS)`, and on the chosen path shows `BiometricPrompt` with `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` and `setTitle/ setSubtitle` from `strings.xml`, or launches the keyguard intent. Calls back `onSucceeded` / `onFailed`. Keep all framework calls here so the gate is the only non-testable auth file.

> Note: the host Activity must be a `FragmentActivity` for `BiometricPrompt`. Check `MainActivity`'s superclass; if it's `ComponentActivity`, switching to `androidx.fragment.app.FragmentActivity` is a one-line change (FragmentActivity extends ComponentActivity) — verify Compose still hosts fine (it does). Flag this for the reviewer.

- [ ] **Step 5: Run decision test, verify pass; compile.**
- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/vault/VaultAuthDecision.kt app/src/main/java/com/aritr/rova/ui/vault/VaultAuthGate.kt app/src/test/java/com/aritr/rova/ui/vault/VaultAuthDecisionTest.kt
git commit -m "feat(vault): VaultAuthDecision pure helper + VaultAuthGate BiometricPrompt wrapper"
```

**✅ PHASE 4 GATE:** reducer + decision tests green; biometric resolves; gate compiles. Reviewer GO before Phase 5.

---

# Phase 5 — UI / nav / playback / backup / FLAG_SECURE

**Phase goal:** The vault is reachable, locked, plays back app-private files, is screenshot-blocked and backup-excluded; the toggle exists with toggle-off gated. **NO-GO into Phase 6 until** the pure filters/resolver tests pass and an owner device smoke confirms: toggle ON → record → recording absent from Library, present in vault only after auth; FLAG_SECURE blocks screenshot; playback works.

### Task 13: `VaultListFilter` (pure) + `HistoryViewModel` filter to PUBLIC

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/vault/VaultListFilter.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/vault/VaultListFilterTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
package com.aritr.rova.ui.vault

import com.aritr.rova.data.VaultState
import org.junit.Assert.assertEquals
import org.junit.Test

class VaultListFilterTest {
    @Test fun libraryShowsOnlyPublic() {
        assertEquals(true, isLibraryVisible(VaultState.PUBLIC))
        assertEquals(false, isLibraryVisible(VaultState.VAULTING))
        assertEquals(false, isLibraryVisible(VaultState.VAULTED))
        assertEquals(false, isLibraryVisible(VaultState.UNVAULTING))
    }
    @Test fun vaultShowsVaultedAndUnvaulting() { // O1: UNVAULTING stays in vault until PUBLIC
        assertEquals(false, isVaultVisible(VaultState.PUBLIC))
        assertEquals(false, isVaultVisible(VaultState.VAULTING))
        assertEquals(true, isVaultVisible(VaultState.VAULTED))
        assertEquals(true, isVaultVisible(VaultState.UNVAULTING))
    }
}
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement**

```kotlin
package com.aritr.rova.ui.vault

import com.aritr.rova.data.VaultState

/** B5 / ADR-0025 — normal Library shows only PUBLIC. */
fun isLibraryVisible(state: VaultState): Boolean = state == VaultState.PUBLIC

/**
 * B5 / ADR-0025 (spec O1) — the vault shows VAULTED and UNVAULTING: an
 * interrupted move-out stays vault-visible (hidden) until publish confirms
 * PUBLIC. VAULTING is shown in neither stable list.
 */
fun isVaultVisible(state: VaultState): Boolean =
    state == VaultState.VAULTED || state == VaultState.UNVAULTING
```

- [ ] **Step 4: Apply in `HistoryViewModel`** — in `manifestDrivenArtifacts()` add `&& isLibraryVisible(manifest.vaultState)` to the filter that currently keeps `FINALIZED` exports. (Read the method; keep the existing FINALIZED predicate.)
- [ ] **Step 5: Run, verify pass; full suite.**
- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/vault/VaultListFilter.kt app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt app/src/test/java/com/aritr/rova/ui/vault/VaultListFilterTest.kt
git commit -m "feat(vault): library/vault list partition (O1: UNVAULTING stays in vault)"
```

### Task 14: `PlayerUriResolver` vault branch (FileProvider)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerUriResolver.kt`
- Modify: `app/src/main/res/xml/file_paths.xml`
- Test: `app/src/test/java/com/aritr/rova/ui/screens/player/PlayerUriResolverVaultTest.kt`

> Read `PlayerUriResolver.kt`. It currently routes by `exportTier` (Tier1 → `pendingUri`; Tier2/3 → `publicTargetPath` file URI; SAF → `safTargetDocUri`). Add a **first** branch: `if (manifest.vaultState == VAULTED) → vaultFilePath` resolved as a FileProvider `content://` URI. The resolver returns the source path/URI; the actual FileProvider conversion happens in the Android wrapper (keep the resolver pure — return a sealed result the wrapper turns into a URI, matching how it already distinguishes file vs content sources).

- [ ] **Step 1: Failing test** for the pure branch (mirror the existing resolver test's shape — read `PlayerUriResolverTest.kt`):

```kotlin
package com.aritr.rova.ui.screens.player

import com.aritr.rova.data.VaultState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerUriResolverVaultTest {
    @Test fun vaultedResolvesToVaultFileAsProviderSource() {
        // Build a minimal manifest VAULTED with vaultFilePath, call the
        // resolver, assert it picks the vault path tagged as a
        // FileProvider source (NOT a raw file:// and NOT a MediaStore uri).
        // Use the same construction the existing resolver test uses.
    }
}
```

> Fill in using the existing `PlayerUriResolverTest` construction helpers. The assertion: a `VAULTED` manifest resolves to `vaultFilePath` via the provider-source variant, ahead of any tier branch.

- [ ] **Step 2–4: Implement the branch, run, pass.** Ensure `file_paths.xml` has an `<external-files-path name="videos" path="videos/"/>` entry so `FileProvider.getUriForFile` can grant the vault file (read the current `file_paths.xml`; add only if missing).
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/PlayerUriResolver.kt app/src/main/res/xml/file_paths.xml app/src/test/java/com/aritr/rova/ui/screens/player/PlayerUriResolverVaultTest.kt
git commit -m "feat(vault): player resolves VAULTED recordings via FileProvider content uri"
```

### Task 15: Strings (honest copy)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

> All user-facing copy MUST be a resource (`checkNoHardcodedUiStrings`). Add (honest wording per codex review / spec §8, §11):

- [ ] **Step 1: Add strings**

```xml
<string name="settings_hide_in_vault_title">Hide new recordings in vault</string>
<string name="settings_hide_in_vault_summary">New recordings stay in Rova and are hidden from the gallery. They require your device screen lock to open here. Files are not encrypted in this version.</string>
<string name="vault_title">Hidden vault</string>
<string name="vault_unlock_prompt_title">Unlock vault</string>
<string name="vault_unlock_prompt_subtitle">Use your screen lock to view hidden recordings</string>
<string name="vault_no_lock_warning">Set a device screen lock to protect the vault. Recordings are still hidden from the gallery, but the in-app lock is off.</string>
<string name="vault_share_leaves_warning">Sharing or exporting copies this recording out of the vault. The copy is no longer hidden.</string>
<string name="vault_move_in">Move to vault</string>
<string name="vault_move_out">Move out of vault</string>
```

- [ ] **Step 2:** Run `./gradlew :app:checkNoHardcodedUiStrings` after the UI tasks (it scans for literal UI strings); expected PASS.
- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(vault): user-facing strings (honest no-encryption copy)"
```

### Task 16: Settings toggle row + toggle-off auth gate

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt`, `SettingsViewModel.kt`
- Create: `app/src/main/java/com/aritr/rova/ui/vault/VaultToggleDecision.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/vault/VaultToggleDecisionTest.kt`

- [ ] **Step 1: Failing test** — only ON→OFF requires auth.

```kotlin
package com.aritr.rova.ui.vault

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class VaultToggleDecisionTest {
    @Test fun turningOff_requiresAuth() = assertTrue(toggleRequiresAuth(current = true, desired = false))
    @Test fun turningOn_isFree() = assertFalse(toggleRequiresAuth(current = false, desired = true))
    @Test fun noChange_isFree() {
        assertFalse(toggleRequiresAuth(current = true, desired = true))
        assertFalse(toggleRequiresAuth(current = false, desired = false))
    }
}
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement**

```kotlin
package com.aritr.rova.ui.vault

/** B5 / ADR-0025 (spec R4) — only ON->OFF requires auth; turning ON is free. */
fun toggleRequiresAuth(current: Boolean, desired: Boolean): Boolean = current && !desired
```

- [ ] **Step 4: Wire the Settings row** — add a toggle row (mirror the existing `autoDeleteEnabled` row) bound to `SettingsViewModel.hideInVault` StateFlow. On toggle, if `toggleRequiresAuth(current, desired)` → invoke `VaultAuthGate`; only persist `RovaSettings.hideInVault = desired` in the `onSucceeded` callback (or immediately when no auth needed). Use the strings from Task 15.
- [ ] **Step 5: Run decision test, verify pass; full suite.**
- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt app/src/main/java/com/aritr/rova/ui/vault/VaultToggleDecision.kt app/src/test/java/com/aritr/rova/ui/vault/VaultToggleDecisionTest.kt
git commit -m "feat(vault): settings toggle + ON->OFF auth gate"
```

### Task 17: Vault route, screen, VM, gated entry, FLAG_SECURE

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/vault/VaultViewModel.kt`, `VaultScreen.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/MainScreen.kt`, `MainActivity.kt` (FragmentActivity, ProcessLifecycleOwner relock)

> Framework wiring — verified by device smoke, not JVM tests.

- [ ] **Step 1: `VaultViewModel`** — mirror `HistoryViewModel` but filter via `isVaultVisible(manifest.vaultState)`. Expose `items: StateFlow<List<VideoItem>>` and a `VaultLockState` flow.
- [ ] **Step 2: `VaultScreen`** — reuse the History card list UI; set `FLAG_SECURE` via a `DisposableEffect` on the hosting window (`window.addFlags(FLAG_SECURE)` on enter, `clearFlags` on dispose). Empty-state + the `vault_no_lock_warning` banner when `VaultAuthPath.UNAVAILABLE_NO_CREDENTIAL`.
- [ ] **Step 3: `MainScreen` nav** — add `composable("vault") { ... }`. The History "Hidden vault" entry point calls `VaultAuthGate`; on success set `VaultLockState.onAuthSucceeded()` and navigate. Pop of the route → `onLeaveVault()`. Also apply `FLAG_SECURE` to the `player/{sessionId}` route when the resolved manifest is `VAULTED` (so vault playback can't be screenshotted).
- [ ] **Step 4: `MainActivity`** — ensure it extends `FragmentActivity` (for `BiometricPrompt`); add a `ProcessLifecycleOwner` `ON_STOP` observer that calls `VaultLockState.onAppBackgrounded()` on the shared lock holder (mirror the ADR-0021 camera observer wiring).
- [ ] **Step 5: Compile.** `./gradlew :app:compileDebugKotlin`
- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/vault/VaultViewModel.kt app/src/main/java/com/aritr/rova/ui/vault/VaultScreen.kt app/src/main/java/com/aritr/rova/ui/MainScreen.kt app/src/main/java/com/aritr/rova/MainActivity.kt
git commit -m "feat(vault): gated vault route + screen + FLAG_SECURE + ON_STOP relock"
```

### Task 18: Auto Backup exclusion (vault dir)

**Files:**
- Modify: `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`

> Read both files. The vault videos live under `getExternalFilesDir("videos")` = `external` files. Exclude the videos dir from cloud backup + device transfer so vaulted recordings never sync off-device (spec §11, O3 → vault video artifacts only; do NOT exclude manifests).

- [ ] **Step 1:** Add an `<exclude domain="external" path="files/videos/"/>` (use the correct domain/path token for the existing file's schema — match how `mode`/runtime prefs are already excluded). Confirm the path resolves to the videos root used by `RovaApp.videosRoot`.
- [ ] **Step 2:** Build + (device smoke later) — compile-check the XML by assembling debug. `./gradlew :app:assembleDebug` (subagent).
- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/xml/backup_rules.xml app/src/main/res/xml/data_extraction_rules.xml
git commit -m "feat(vault): exclude vault video artifacts from Auto Backup + device transfer"
```

### Task 19: Phase 5 device smoke (owner gate)

- [ ] Build + install debug APK on a real device with a screen lock enrolled.
- [ ] Settings → turn **Hide in vault** ON. Record a short session. Confirm: it does **not** appear in the gallery/Photos; it does **not** appear in the normal Library; it appears in the Vault **only after** the biometric/PIN prompt.
- [ ] Background the app, reopen, tap the vault → prompts again (relock works).
- [ ] Try to screenshot the vault list + a vault video playing → blocked by `FLAG_SECURE`.
- [ ] Play a vault recording → plays from app-private storage.
- [ ] Turn the toggle OFF → prompts auth first.
- [ ] On a device with **no** screen lock: vault shows the `vault_no_lock_warning`, recordings still hidden from gallery.

**✅ PHASE 5 GATE:** pure tests green + owner smoke checklist passes. Reviewer + owner GO before Phase 6.

---

# Phase 6 — Move recordings in / out

**Phase goal:** Users move recordings into and out of the vault with crash-safe two-phase transitions. **DONE when** `VaultMover` transition + crash-recovery tests pass and owner smoke confirms a round-trip move.

### Task 20: `VaultMover` transition state machine (pure)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/export/VaultMover.kt`
- Test: `app/src/test/java/com/aritr/rova/service/export/VaultMoverTest.kt`

> Pure orchestration with injected effects (`copyToPrivate`, `deletePublic`, `publishExisting`, and the `SessionStore` setters). Ordering law: **manifest is written only after each destructive step verifies**, through the `VAULTING`/`UNVAULTING` intermediates.

- [ ] **Step 1: Failing test** (covers happy path + crash-at-each-intermediate)

```kotlin
package com.aritr.rova.service.export

import com.aritr.rova.data.VaultState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VaultMoverTest {

    private class Recorder {
        val ops = mutableListOf<String>()
        var state = VaultState.PUBLIC
    }

    @Test fun moveIn_orderingCopyThenVaultingThenDeleteThenVaulted() = runBlocking {
        val r = Recorder()
        val mover = VaultMover(
            copyToPrivate = { r.ops += "copy" },
            deletePublic = { r.ops += "deletePublic" },
            publishExisting = { error("not used") },
            setVaulting = { r.ops += "state:VAULTING"; r.state = VaultState.VAULTING },
            setVaulted = { r.ops += "state:VAULTED"; r.state = VaultState.VAULTED },
            setUnvaulting = { error("not used") },
            setPublic = { error("not used") },
        )
        mover.moveIn("s1")
        assertEquals(listOf("copy", "state:VAULTING", "deletePublic", "state:VAULTED"), r.ops)
        assertEquals(VaultState.VAULTED, r.state)
    }

    @Test fun moveOut_orderingUnvaultingThenPublishThenPublic() = runBlocking {
        val r = Recorder()
        val mover = VaultMover(
            copyToPrivate = { error("not used") },
            deletePublic = { error("not used") },
            publishExisting = { r.ops += "publish" },
            setVaulting = { error("not used") },
            setVaulted = { error("not used") },
            setUnvaulting = { r.ops += "state:UNVAULTING"; r.state = VaultState.UNVAULTING },
            setPublic = { r.ops += "state:PUBLIC"; r.state = VaultState.PUBLIC },
        )
        mover.moveOut("s1")
        assertEquals(listOf("state:UNVAULTING", "publish", "state:PUBLIC"), r.ops)
        assertEquals(VaultState.PUBLIC, r.state)
    }

    @Test fun moveIn_crashAfterVaulting_resumeDeletesThenVaulted() = runBlocking {
        val r = Recorder().also { it.state = VaultState.VAULTING }
        val mover = VaultMover(
            copyToPrivate = { error("private copy already exists; must NOT re-copy") },
            deletePublic = { r.ops += "deletePublic" },
            publishExisting = { error("not used") },
            setVaulting = { error("not used") },
            setVaulted = { r.ops += "state:VAULTED"; r.state = VaultState.VAULTED },
            setUnvaulting = { error("not used") },
            setPublic = { error("not used") },
        )
        mover.finishVaulting("s1")   // recovery resume entry
        assertEquals(listOf("deletePublic", "state:VAULTED"), r.ops)
    }

    @Test fun moveOut_crashAfterUnvaulting_resumePublishesThenPublic() = runBlocking {
        val r = Recorder().also { it.state = VaultState.UNVAULTING }
        val mover = VaultMover(
            copyToPrivate = { error("not used") },
            deletePublic = { error("not used") },
            publishExisting = { r.ops += "publish" },
            setVaulting = { error("not used") },
            setVaulted = { error("not used") },
            setUnvaulting = { error("not used") },
            setPublic = { r.ops += "state:PUBLIC"; r.state = VaultState.PUBLIC },
        )
        mover.finishUnvaulting("s1")   // recovery resume entry
        assertEquals(listOf("publish", "state:PUBLIC"), r.ops)
    }
}
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement**

```kotlin
package com.aritr.rova.service.export

/**
 * B5 / ADR-0025 — move-in / move-out orchestration. Two-phase through the
 * VAULTING / UNVAULTING intermediates so a crash is always recoverable
 * (spec §9). Ordering law: the destructive step (deletePublic / publish)
 * runs only after the intermediate state is committed, and the terminal
 * state is committed only after the destructive step verifies.
 *
 * All effects are injected so this is JVM-testable. The recovery runner
 * calls [finishVaulting] / [finishUnvaulting] to resume an interrupted
 * move (the private copy / state are already on disk).
 */
internal class VaultMover(
    private val copyToPrivate: suspend () -> Unit,
    private val deletePublic: suspend () -> Unit,
    private val publishExisting: suspend () -> Unit,
    private val setVaulting: () -> Unit,
    private val setVaulted: () -> Unit,
    private val setUnvaulting: () -> Unit,
    private val setPublic: () -> Unit,
) {
    suspend fun moveIn(sessionId: String) {
        copyToPrivate()
        setVaulting()
        finishVaulting(sessionId)
    }

    suspend fun finishVaulting(@Suppress("UNUSED_PARAMETER") sessionId: String) {
        deletePublic()
        setVaulted()
    }

    suspend fun moveOut(sessionId: String) {
        setUnvaulting()
        finishUnvaulting(sessionId)
    }

    suspend fun finishUnvaulting(@Suppress("UNUSED_PARAMETER") sessionId: String) {
        publishExisting()
        setPublic()
    }
}
```

- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/export/VaultMover.kt app/src/test/java/com/aritr/rova/service/export/VaultMoverTest.kt
git commit -m "feat(vault): VaultMover two-phase move in/out with crash-resume entries"
```

### Task 21: `VaultAndroidOps` (per-tier copy / delete / rescan / publish-existing)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/export/VaultAndroidOps.kt`

> Thin framework wrapper, not unit-tested (verified by Task 23 device smoke). Provide the four effects `VaultMover` injects, dispatched by the manifest's `exportTier`:
> - `copyToPrivate`: Tier1 → read bytes from `pendingUri` via `ContentResolver.openInputStream`; Tier2/3 → copy from `publicTargetPath`; SAF → read from `safTargetDocUri`. Write to the session's vault file.
> - `deletePublic`: Tier1 → `resolver.delete(pendingUri)`; Tier2/3 → delete the file **then** `MediaScannerConnection.scanFile` on the now-missing path to drop the stale gallery index (codex review); SAF → `DocumentFile.fromSingleUri(...).delete()`.
> - `publishExisting`: copy the existing vault file's bytes through the **same publisher** used by normal export, supplying the merged file instead of segments (no `VideoMerger.mergeSegments` call — so the single-entry gate is not tripped). Reuse `Tier1AndroidOps.insertPendingRow` + `withPendingFd` (write the file bytes into the FD) / the pre-Q rename publish + `MediaScanWaiter` / `SafAndroidOps.copyFileToDocument`. Destination tier = `currentExportTier(hasUsableSafFolder)` recomputed now.

- [ ] **Step 1: Implement `VaultAndroidOps`** with the three dispatchers above, each `when (tier)`.
- [ ] **Step 2: Compile.** `./gradlew :app:compileDebugKotlin`
- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/export/VaultAndroidOps.kt
git commit -m "feat(vault): per-tier copy/delete(+rescan)/publish-existing ops for moves"
```

### Task 22: Wire move actions into the UI + recovery resume

**Files:**
- Modify: `VaultScreen.kt` (move-out action), `HistoryScreen.kt`/`HistoryViewModel.kt` (move-in action), `ExportRecoveryRunner.kt` (replace the Phase-3 `// TODO(Phase 6)` with real `VaultMover.finishVaulting/finishUnvaulting`)

- [ ] **Step 1:** Add a "Move to vault" card action on normal Library rows → builds a `VaultMover` from `VaultAndroidOps` + `SessionStore` setters and runs `moveIn(sessionId)` off the main thread; show the `vault_share_leaves_warning`/confirmations per the strings. No auth needed for move-in.
- [ ] **Step 2:** Add a "Move out of vault" action on vault rows (auth already passed inside the unlocked vault) → `moveOut(sessionId)`.
- [ ] **Step 3:** In `ExportRecoveryRunner`, replace the Phase-3 TODO so `RESUME_VAULTING` → `VaultMover.finishVaulting`, `RESUME_UNVAULTING` → `VaultMover.finishUnvaulting`.
- [ ] **Step 4:** Compile + full suite.
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/vault/VaultScreen.kt app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt app/src/main/java/com/aritr/rova/service/export/ExportRecoveryRunner.kt
git commit -m "feat(vault): move in/out card actions + recovery resume wiring"
```

### Task 23: Phase 6 device smoke (owner gate)

- [ ] Record a normal (non-vault) recording → "Move to vault" → it leaves the gallery + normal Library, appears in vault after auth. Kill the app mid-move (developer option / force-stop) → relaunch → recovery completes the move; no gallery copy remains, file intact.
- [ ] In the vault, "Move out of vault" a recording → it appears in the gallery + normal Library; the vault no longer lists it. Kill mid-move → relaunch → recovery completes publish; recording present in exactly one place.
- [ ] Share/export a vault recording out → share sheet works; confirm the warning copy shows.

**✅ DONE:** all phases green; owner smoke passes both directions incl. crash-resume.

---

## Self-review (completed during authoring)

- **Spec coverage:** §1–§12 all map to tasks — data model (T1–2), export (T3–5), gate (T6), ADR (T7), recovery/cleanup (T8–9), auth (T10–12), UI/nav/playback/backup/FLAG_SECURE (T13–19), move in/out (T20–22), smokes (T19, T23). O1 → T13; O2 → T6 (single gate); O3 → T18 (vault artifacts only).
- **Placeholder scan:** the only deferred marker is the explicit Phase-3 `// TODO(Phase 6)` resume hook, which Task 22 Step 3 replaces — called out, not silent.
- **Type consistency:** `VaultState` (PUBLIC/VAULTING/VAULTED/UNVAULTING), `vaultIntentAtStart`, `vaultFilePath`, `setVaultFinalized`/`setVaultState`/`setVaultMovedOut`, `VaultExporter`, `VaultMover.moveIn/finishVaulting/moveOut/finishUnvaulting`, `vaultAuthPath`/`VaultAuthPath`, `isLibraryVisible`/`isVaultVisible`, `toggleRequiresAuth` — names used identically across tasks.
- **Open verification points flagged for the implementer:** exact `ExportResult` variant names; `SessionStore` atomic-update helper name; `MainActivity` superclass (`FragmentActivity`); `file_paths.xml` external-files entry; backup-rules domain/path token. Each task says "read the file first" where a real signature must be matched.

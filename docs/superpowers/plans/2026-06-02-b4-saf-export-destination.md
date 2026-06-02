# B4b — Custom SAF Save-Location Export Destination — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user pick a custom folder (via SAF) as the save location; finished exports are written there instead of MediaStore/Movies, with full crash-recovery and no data-loss window.

**Architecture:** SAF is a new frozen export route (`ExportTier.SAF_DESTINATION`) that reuses the preQ private-temp rails — mux to a local temp, validate, copy the bytes into the user-chosen SAF document, validate, then clear `privateTempPath`. The muxer never touches a SAF descriptor (seekability is not portable). The route is frozen per session into the manifest and dispatched on recovery by `manifest.exportTier`, exactly like the SDK-derived tiers.

**Tech Stack:** Kotlin, Android Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`, `DocumentsContract`, `ContentResolver.openOutputStream`), JUnit (pure-JVM, `isReturnDefaultValues = true`, `org.json` on testImplementation), Compose (`ActivityResultContracts.OpenDocumentTree`).

**Spec:** `docs/superpowers/specs/2026-06-02-b4-saf-export-destination-design.md`
**Branch:** `feat/b4-saf-export` (already created off master).

---

## Read-this-first context (no codebase spelunking required)

- **`currentExportTier()`** lives in `app/src/main/java/com/aritr/rova/data/SessionManifest.kt` (~L263). Today no-arg, SDK-only. Frozen into the manifest at **`SessionStore.createSession`** (~L118: `val tier = currentExportTier()`), and used for byte-estimation in `RovaRecordingService` preflight (~L1060).
- **`ExportTier`** enum (3 values) is in the same file (~L246). Adding `SAF_DESTINATION` will make several `when (exportTier)` blocks fail to compile — that is intentional (Kotlin exhaustiveness = the audit). The known sites are: `ExportPipeline.export()`, `ExportPipeline.exportPreQ()`, `TierArtifactValidator.isArtifactValid()` (two `when`s), `RovaApp` `tierRecover`, and `RovaRecordingService.estimatePeakBytes(...)`. Fix every one the compiler surfaces.
- **`ExportResult`** sealed class is in `app/src/main/java/com/aritr/rova/service/export/ExportTypes.kt`. Adding `SafFolderUnavailable` will make every `when (result: ExportResult)` fail to compile — fix each (the live result-dispatch in `RovaRecordingService.performMerge`, plus any signal mapper). For non-success arms, do **not** write a terminal `COMPLETED`.
- **Manifest serialization pattern** (`SessionManifest.kt`): nullable String → `value?.let { put("key", it) }` in `toJson`, `json.optString("key", "").ifEmpty { null }` in `fromJson`. Int → `put("key", intValue)` / `json.optInt("key", 0)`. `SCHEMA_VERSION` (~L122) is write-only; bump it for documentation but `fromJson` relies on per-field defaults (old manifests parse fine).
- **SessionStore export setters** all funnel through `private suspend fun mutateExport(label, sessionId) { current -> current.copy(...) }` which loads → transforms → `writeManifestAtomic` with a 3-attempt retry, returning `ExportMutationResult`. Mirror `setExportPrivateTarget` / `setExportCopying`.
- **House test policy:** pure-JVM only. Anything touching `ContentResolver` / `DocumentsContract` / `SharedPreferences` is a thin seam injected as a lambda and is **not** unit-tested directly (mirrors the untested `Tier1AndroidOps`). The logic around it is tested via injected fakes.
- **Run a single test:** `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.<Fqcn>"` (Windows: `gradlew.bat`; route Gradle through the PowerShell tool).
- **Static-check gate:** new invariants need ADR clause → `check*` task → `preBuild` wiring (Task 10). Don't weaken an existing check.

---

## File structure

**Create:**
- `app/src/main/java/com/aritr/rova/service/export/SafAndroidOps.kt` — thin Android seam: create document, open output stream, validate doc, delete doc, write-probe, permission-held probe. (Task 1)
- `app/src/main/java/com/aritr/rova/service/export/SafExporter.kt` — preQ-style SAF route exporter (side-parameterized, injected lambdas). (Task 4)
- `app/src/main/java/com/aritr/rova/ui/signals/SaveFolderSignal.kt` — leaf signal for the unavailable-folder warning. (Task 8)
- `app/build.gradle.kts` task `checkSafTargetCommittedBeforeStream`. (Task 10)
- `docs/adr/0024-saf-export-destination.md`. (Task 10)
- Tests: `SessionManifestSafFieldsTest`, `SessionStoreSafTargetTest`, `SafExporterTest`, `CurrentExportTierSafTest`, `TierArtifactValidatorSafTest`, `WarningPrecedenceSaveFolderTest`, plus updates to `WarningIdOrderTest`.

**Modify:**
- `data/SessionManifest.kt` — `ExportTier.SAF_DESTINATION`, `currentExportTier(hasUsableSafFolder)`, 4 new manifest fields + serialization, `SCHEMA_VERSION` 5→6. (Tasks 2, 5)
- `service/export/ExportTypes.kt` — `ExportResult.SafFolderUnavailable`. (Task 4)
- `data/SessionStore.kt` — new SAF setters + `createSession(hasUsableSafFolder)`. (Tasks 3, 6)
- `service/export/ExportPipeline.kt` — SAF branch + honor frozen tier + `exportPreQ` SAF guard. (Task 5)
- `service/export/TierArtifactValidator.kt` — `safProbe` param + SAF branches. (Task 5)
- `RovaApp.kt` — `tierRecover` SAF arm, `validateTierArtifact` safProbe, `SaveFolderSignal` lazy, WarningCenter wiring. (Tasks 5, 8)
- `service/RovaRecordingService.kt` — compute `hasUsableSafFolder`, pass to `createSession`, `estimatePeakBytes` SAF arm, `SafFolderUnavailable` result arm. (Task 6)
- `data/RovaSettings.kt` — `saveLocationTreeUri`, `saveLocationLabel`, `saveFolderUnavailable`. (Tasks 6, 8)
- `ui/screens/SettingsViewModel.kt` + `SettingsScreen.kt` — Save-location row + SAF picker. (Task 7)
- `ui/warnings/WarningId.kt`, `WarningPrecedence.kt`, `WarningCenter.kt`, `WarningCenterViewModel.kt` — `SAVE_FOLDER_UNAVAILABLE`. (Task 8)
- `CHANGELOG.md`. (Task 10)

---

## Task 1: SafAndroidOps seam

Thin Android wrapper. No unit test (framework seam, like `Tier1AndroidOps`). The functions are injected into `SafExporter` as lambdas so the exporter stays pure.

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/export/SafAndroidOps.kt`

- [ ] **Step 1: Create the seam**

```kotlin
package com.aritr.rova.service.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.aritr.rova.utils.RovaLog
import java.io.File

/**
 * ADR-0024 §SAF route. Thin Android seam for the Storage Access
 * Framework publish step. Not unit-tested (mirrors Tier1AndroidOps);
 * SafExporter injects these as lambdas and is tested with fakes.
 *
 * The muxer never touches SAF — these helpers only run AFTER a local
 * private-temp MP4 already exists. The publish is a sequential byte
 * copy (openOutputStream), so non-seekable / cloud providers still work.
 */
internal object SafAndroidOps {

    /** True iff a persisted read+write grant for [treeUri] is still held. */
    fun isPersistedPermissionHeld(resolver: ContentResolver, treeUri: String): Boolean {
        val target = Uri.parse(treeUri)
        return resolver.persistedUriPermissions.any {
            it.uri == target && it.isWritePermission && it.isReadPermission
        }
    }

    /**
     * Create a child document under [treeUri]. Returns the created doc
     * Uri string, or null if creation returned null. May throw — caller
     * classifies the throwable (permission-held → transient, else permanent).
     * The provider MAY auto-rename; caller reads back the real name via
     * [displayNameOf].
     */
    fun createDocument(resolver: ContentResolver, treeUri: String, displayName: String): String? {
        val tree = Uri.parse(treeUri)
        val docTreeUri = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        val created = DocumentsContract.createDocument(resolver, docTreeUri, "video/mp4", displayName)
        return created?.toString()
    }

    /** The provider-authoritative display name of [docUri], or null. */
    fun displayNameOf(resolver: ContentResolver, docUri: String): String? {
        resolver.query(Uri.parse(docUri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    /** Sequential byte copy [src] → the SAF document [docUri]. May throw. */
    fun copyFileToDocument(resolver: ContentResolver, src: File, docUri: String) {
        resolver.openOutputStream(Uri.parse(docUri), "w")?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: throw java.io.IOException("openOutputStream returned null for $docUri")
    }

    /** Valid iff the doc exists with non-zero length. Best-effort; false on throw. */
    fun validateDocument(resolver: ContentResolver, docUri: String): Boolean = try {
        resolver.query(Uri.parse(docUri), arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            ?.use { c -> c.moveToFirst() && c.getLong(0) > 0L } ?: false
    } catch (t: Throwable) {
        RovaLog.w("SafAndroidOps.validateDocument threw for $docUri", t)
        false
    }

    /** Best-effort delete of a partial/failed doc. Returns true on success. */
    fun deleteDocument(resolver: ContentResolver, docUri: String): Boolean = try {
        DocumentsContract.deleteDocument(resolver, Uri.parse(docUri))
    } catch (t: Throwable) {
        RovaLog.w("SafAndroidOps.deleteDocument threw for $docUri", t)
        false
    }

    /**
     * Pick-time validation: create a tiny doc, write a byte, delete it.
     * Returns true iff the tree accepts our writes. Used by the Settings
     * picker to reject unusable providers before persisting the choice.
     */
    fun writeProbe(resolver: ContentResolver, treeUri: String): Boolean {
        var probeUri: String? = null
        return try {
            probeUri = createDocument(resolver, treeUri, "rova_probe_${System.nanoTime()}.tmp")
                ?: return false
            resolver.openOutputStream(Uri.parse(probeUri), "w")?.use { it.write(byteArrayOf(0)) }
                ?: return false
            true
        } catch (t: Throwable) {
            RovaLog.w("SafAndroidOps.writeProbe failed for $treeUri", t)
            false
        } finally {
            probeUri?.let { deleteDocument(resolver, it) }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/export/SafAndroidOps.kt
git commit -m "feat(b4): SafAndroidOps seam for SAF publish (ADR-0024)"
```

---

## Task 2: Manifest SAF fields + schema bump

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/SessionManifest.kt`
- Test: `app/src/test/java/com/aritr/rova/data/SessionManifestSafFieldsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionManifestSafFieldsTest {

    private fun base() = SessionManifest(
        sessionId = "s1",
        startedAt = 1L,
        config = SessionConfig(10, 1, "1080p", 3, "Portrait"),
        segments = emptyList(),
        exportTier = ExportTier.TIER1_API29_PLUS
    )

    @Test
    fun safFields_default_null_and_zero() {
        val m = base()
        assertNull(m.safTargetDocUri)
        assertNull(m.portraitSafTargetDocUri)
        assertNull(m.landscapeSafTargetDocUri)
        assertEquals(0, m.safTransientRetryCount)
    }

    @Test
    fun safFields_roundTrip_through_json() {
        val m = base().copy(
            safTargetDocUri = "content://tree/doc/42",
            portraitSafTargetDocUri = "content://tree/doc/p",
            landscapeSafTargetDocUri = "content://tree/doc/l",
            safTransientRetryCount = 2
        )
        val restored = SessionManifest.fromJson(m.toJson())
        assertEquals("content://tree/doc/42", restored.safTargetDocUri)
        assertEquals("content://tree/doc/p", restored.portraitSafTargetDocUri)
        assertEquals("content://tree/doc/l", restored.landscapeSafTargetDocUri)
        assertEquals(2, restored.safTransientRetryCount)
    }

    @Test
    fun safFields_absent_in_old_json_parse_to_defaults() {
        // A schema-5 manifest with no SAF keys must still parse.
        val json = base().toJson()
        json.remove("safTargetDocUri")
        json.remove("safTransientRetryCount")
        val restored = SessionManifest.fromJson(json)
        assertNull(restored.safTargetDocUri)
        assertEquals(0, restored.safTransientRetryCount)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.data.SessionManifestSafFieldsTest"`
Expected: FAIL — unresolved `safTargetDocUri`.

- [ ] **Step 3: Add the fields to the constructor**

In `SessionManifest(...)`, append after `landscapeMediaScanCompleted`:

```kotlin
    val landscapeMediaScanCompleted: Boolean = false,
    val safTargetDocUri: String? = null,
    val portraitSafTargetDocUri: String? = null,
    val landscapeSafTargetDocUri: String? = null,
    val safTransientRetryCount: Int = 0
) {
```

- [ ] **Step 4: Serialize in `toJson()`** (beside the other nullable-String puts)

```kotlin
        safTargetDocUri?.let { put("safTargetDocUri", it) }
        portraitSafTargetDocUri?.let { put("portraitSafTargetDocUri", it) }
        landscapeSafTargetDocUri?.let { put("landscapeSafTargetDocUri", it) }
        if (safTransientRetryCount > 0) put("safTransientRetryCount", safTransientRetryCount)
```

- [ ] **Step 5: Parse in `fromJson()`** (beside the other field parses, inside the `SessionManifest(...)` call)

```kotlin
            safTargetDocUri = json.optString("safTargetDocUri", "").ifEmpty { null },
            portraitSafTargetDocUri = json.optString("portraitSafTargetDocUri", "").ifEmpty { null },
            landscapeSafTargetDocUri = json.optString("landscapeSafTargetDocUri", "").ifEmpty { null },
            safTransientRetryCount = json.optInt("safTransientRetryCount", 0)
```

- [ ] **Step 6: Bump schema version** (~L122)

```kotlin
        const val SCHEMA_VERSION = 6   // 5→6: SAF export-route fields (ADR-0024)
```

- [ ] **Step 7: Run the test — expect PASS**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.data.SessionManifestSafFieldsTest"`
Expected: PASS (3/3).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/SessionManifest.kt app/src/test/java/com/aritr/rova/data/SessionManifestSafFieldsTest.kt
git commit -m "feat(b4): manifest SAF route fields + schema 5->6 (ADR-0024)"
```

---

## Task 3: SessionStore SAF setters

Three setters mirroring `setExportPrivateTarget` / `setExportCopying`, plus a retry-counter incrementer and per-side variants.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/SessionStore.kt`
- Test: `app/src/test/java/com/aritr/rova/data/SessionStoreSafTargetTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.data

import com.aritr.rova.service.dualrecord.VideoSide
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreSafTargetTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = SessionStore.forRoot(tmp.newFolder("videos"))

    private fun newSession(s: SessionStore): String {
        val id = s.createSession(SessionConfig(10, 1, "1080p", 3, "Portrait"))
        return id
    }

    @Test
    fun setExportSafPrivateTemp_sets_privateTemp_and_MUXING() = runBlocking {
        val s = store(); val id = newSession(s)
        val r = s.setExportSafPrivateTemp(id, "/tmp/x.private")
        assertEquals(ExportMutationResult.Wrote::class, r::class)
        val m = s.loadManifest(id)!!
        assertEquals("/tmp/x.private", m.privateTempPath)
        assertEquals(ExportState.MUXING, m.exportState)
    }

    @Test
    fun setExportSafTarget_sets_docUri_and_COPYING() = runBlocking {
        val s = store(); val id = newSession(s)
        s.setExportSafPrivateTemp(id, "/tmp/x.private")
        s.setExportSafTarget(id, "content://tree/doc/9")
        val m = s.loadManifest(id)!!
        assertEquals("content://tree/doc/9", m.safTargetDocUri)
        assertEquals(ExportState.COPYING, m.exportState)
    }

    @Test
    fun incrementSafTransientRetry_counts_up() = runBlocking {
        val s = store(); val id = newSession(s)
        s.incrementSafTransientRetry(id)
        s.incrementSafTransientRetry(id)
        assertEquals(2, s.loadManifest(id)!!.safTransientRetryCount)
    }

    @Test
    fun perSide_safTarget_sets_side_field() = runBlocking {
        val s = store(); val id = newSession(s)
        s.setExportSafTargetForSide(id, VideoSide.PORTRAIT, "content://tree/doc/p")
        assertEquals("content://tree/doc/p", s.loadManifest(id)!!.portraitSafTargetDocUri)
    }
}
```

> Note: if `SessionStore.forRoot(...)` does not exist, use the existing test construction helper the other `SessionStore*Test` files use (grep `SessionStore` in `app/src/test`); the rest of the test is unchanged.

- [ ] **Step 2: Run it — confirm FAIL** (unresolved `setExportSafPrivateTemp`).

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.data.SessionStoreSafTargetTest"`

- [ ] **Step 3: Add the setters** (next to `setExportCopying`)

```kotlin
    /** ADR-0024 §SAF route. Commit the private temp before the SAF mux; mirrors Tier 2/3 commit-point A. */
    suspend fun setExportSafPrivateTemp(sessionId: String, privateTempPath: String): ExportMutationResult =
        mutateExport("setExportSafPrivateTemp", sessionId) { current ->
            current.copy(privateTempPath = privateTempPath, exportState = ExportState.MUXING)
        }

    /** ADR-0024 §commit-before-stream. Commit the SAF doc Uri BEFORE the first byte is written to it. */
    suspend fun setExportSafTarget(sessionId: String, docUri: String): ExportMutationResult =
        mutateExport("setExportSafTarget", sessionId) { current ->
            current.copy(safTargetDocUri = docUri, exportState = ExportState.COPYING)
        }

    /** ADR-0024 §failure classification. Bump the transient-retry counter (escalates to permanent at 3). */
    suspend fun incrementSafTransientRetry(sessionId: String): ExportMutationResult =
        mutateExport("incrementSafTransientRetry", sessionId) { current ->
            current.copy(safTransientRetryCount = current.safTransientRetryCount + 1)
        }

    suspend fun setExportSafPrivateTempForSide(sessionId: String, side: com.aritr.rova.service.dualrecord.VideoSide, privateTempPath: String): ExportMutationResult =
        mutateExport("setExportSafPrivateTempForSide", sessionId) { current ->
            when (side) {
                com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT -> current.copy(portraitPrivateTempPath = privateTempPath)
                com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE -> current.copy(landscapePrivateTempPath = privateTempPath)
            }
        }

    suspend fun setExportSafTargetForSide(sessionId: String, side: com.aritr.rova.service.dualrecord.VideoSide, docUri: String): ExportMutationResult =
        mutateExport("setExportSafTargetForSide", sessionId) { current ->
            when (side) {
                com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT -> current.copy(portraitSafTargetDocUri = docUri)
                com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE -> current.copy(landscapeSafTargetDocUri = docUri)
            }
        }
```

- [ ] **Step 4: Run the test — expect PASS.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/SessionStore.kt app/src/test/java/com/aritr/rova/data/SessionStoreSafTargetTest.kt
git commit -m "feat(b4): SessionStore SAF setters (private-temp, target, retry, per-side)"
```

---

## Task 4: SafExporter + ExportResult.SafFolderUnavailable

The route exporter. Mirrors `PreQExportCore`'s mux→commit→publish→validate→finalize shape, but the publish is a SAF byte-copy. Pure: all Android ops are injected lambdas. Single-mode and P+L (via `side`).

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/export/ExportTypes.kt`
- Create: `app/src/main/java/com/aritr/rova/service/export/SafExporter.kt`
- Test: `app/src/test/java/com/aritr/rova/service/export/SafExporterTest.kt`

- [ ] **Step 1: Add the new ExportResult variant**

In `ExportTypes.kt`, inside `sealed class ExportResult`:

```kotlin
    /**
     * ADR-0024 §failure classification — PERMANENT SAF failure: the
     * persisted tree permission is gone (user revoked / cleared) or the
     * transient-retry budget (3) is exhausted. Manifest is set FAILED and
     * `WarningId.SAVE_FOLDER_UNAVAILABLE` is surfaced. Segments/private
     * temp are retained so the user can re-pick and resume. Distinct from
     * MuxFailed/CopyFailed which are transient (retained, retried silently).
     */
    data class SafFolderUnavailable(val cause: Throwable?) : ExportResult()
```

- [ ] **Step 2: Write the failing test** (representative cases — implement all)

```kotlin
package com.aritr.rova.service.export

import com.aritr.rova.data.ExportMutationResult
import com.aritr.rova.data.SessionManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafExporterTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"))

    /** Minimal fake store recording the last state; mutate* always Wrote. */
    private class FakeStore {
        val calls = mutableListOf<String>()
        fun wrote(label: String): ExportMutationResult {
            calls += label; return ExportMutationResult.Wrote(
                SessionManifest("s", 0, com.aritr.rova.data.SessionConfig(1,1,"x",1,"Portrait"),
                    emptyList(), com.aritr.rova.data.ExportTier.TIER2_API26_28)
            )
        }
    }

    private fun exporter(
        store: FakeStore,
        mux: suspend (List<File>, File) -> Unit = { _, out -> out.writeBytes(ByteArray(16)) },
        createDoc: (String) -> String? = { "content://tree/doc/1" },
        displayName: (String) -> String? = { "Rova.mp4" },
        copyToDoc: (File, String) -> Unit = { _, _ -> },
        validateDoc: (String) -> Boolean = { true },
        deleteDoc: (String) -> Boolean = { true },
        permissionHeld: () -> Boolean = { true },
        retryCount: Int = 0
    ) = SafExporter(
        treeUri = "content://tree",
        displayName = "Rova.mp4",
        privateTempFile = File(tempDir, "saf_${System.nanoTime()}.private"),
        setSafPrivateTemp = { _ -> store.wrote("setSafPrivateTemp") },
        setSafTarget = { _ -> store.wrote("setSafTarget") },
        setFinalizedClear = { store.wrote("finalizedClear") },
        setFailed = { store.wrote("setFailed") },
        incrementRetry = { store.wrote("incrementRetry") },
        currentRetryCount = { retryCount },
        mux = mux,
        createDocument = createDoc,
        displayNameOf = displayName,
        copyFileToDocument = copyToDoc,
        validateDocument = validateDoc,
        deleteDocument = deleteDoc,
        isPermissionHeld = permissionHeld
    )

    @Test
    fun happyPath_returns_Success_and_finalizes() = runBlocking {
        val store = FakeStore()
        val r = exporter(store).export("s")
        assertTrue(r is ExportResult.Success)
        assertTrue("setSafPrivateTemp" in store.calls)
        assertTrue("setSafTarget" in store.calls)   // committed before copy
        assertTrue("finalizedClear" in store.calls)
    }

    @Test
    fun muxFails_returns_MuxFailed_retained() = runBlocking {
        val r = exporter(FakeStore(), mux = { _, _ -> throw RuntimeException("mux") }).export("s")
        assertTrue(r is ExportResult.MuxFailed)
    }

    @Test
    fun copyFails_permissionHeld_is_transient_CopyFailed() = runBlocking {
        val r = exporter(FakeStore(),
            copyToDoc = { _, _ -> throw java.io.IOException("busy") },
            permissionHeld = { true }
        ).export("s")
        assertTrue(r is ExportResult.CopyFailed)
    }

    @Test
    fun copyFails_permissionGone_is_permanent_SafFolderUnavailable() = runBlocking {
        val r = exporter(FakeStore(),
            copyToDoc = { _, _ -> throw java.io.IOException("revoked") },
            permissionHeld = { false }
        ).export("s")
        assertTrue(r is ExportResult.SafFolderUnavailable)
    }

    @Test
    fun safValidateFails_returns_failure_and_deletes_partial() = runBlocking {
        var deleted = false
        val r = exporter(FakeStore(), validateDoc = { false }, deleteDoc = { deleted = true; true }).export("s")
        assertTrue(r is ExportResult.CopyFailed)
        assertTrue(deleted)
    }

    @Test
    fun createDoc_autoRename_capturesReturnedName() = runBlocking {
        var captured: String? = null
        exporter(FakeStore(),
            createDoc = { "content://tree/doc/renamed" },
            displayName = { "Rova (1).mp4".also { captured = it } }
        ).export("s")
        assertEquals("Rova (1).mp4", captured)
    }

    @Test
    fun recover_validatedDoc_finalizes_without_recopy() = runBlocking {
        var copied = false
        val m = SessionManifest("s", 0, com.aritr.rova.data.SessionConfig(1,1,"x",1,"Portrait"),
            emptyList(), com.aritr.rova.data.ExportTier.SAF_DESTINATION,
            privateTempPath = File(tempDir, "t.private").apply { writeBytes(ByteArray(8)) }.absolutePath,
            safTargetDocUri = "content://tree/doc/good", exportState = com.aritr.rova.data.ExportState.COPYING)
        val r = exporter(FakeStore(),
            validateDoc = { true }, copyToDoc = { _, _ -> copied = true }
        ).recover(m)
        assertTrue(r is RecoveryResult.Resumed)
        assertEquals(false, copied)   // validate-before-delete: never re-copy a good artifact
    }

    @Test
    fun recover_retryBudgetExhausted_escalates_to_permanent() = runBlocking {
        val m = SessionManifest("s", 0, com.aritr.rova.data.SessionConfig(1,1,"x",1,"Portrait"),
            emptyList(), com.aritr.rova.data.ExportTier.SAF_DESTINATION,
            privateTempPath = null, safTargetDocUri = null,
            exportState = com.aritr.rova.data.ExportState.MUXING, safTransientRetryCount = 3)
        val r = exporter(FakeStore(), retryCount = 3, permissionHeld = { false }).recover(m)
        assertTrue(r is RecoveryResult.Resumed && (r as RecoveryResult.Resumed).export is ExportResult.SafFolderUnavailable
            || r is RecoveryResult.Abandoned)
    }
}
```

- [ ] **Step 3: Run it — confirm FAIL** (`SafExporter` unresolved).

- [ ] **Step 4: Implement SafExporter**

```kotlin
package com.aritr.rova.service.export

import com.aritr.rova.data.ExportMutationResult
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.utils.RovaLog
import java.io.File

/**
 * ADR-0024 — SAF export route. A destination variant of the preQ
 * (Tier 2/3) path: mux to a local private temp, validate, then PUBLISH
 * by copying the bytes into the user-chosen SAF document (sequential
 * openOutputStream — no seeking, works on every provider), validate the
 * doc, then finalize (clearing privateTempPath).
 *
 * The muxer NEVER targets a SAF descriptor (seekability is not portable).
 * Crash-safety rides the existing `privateTempPath` retention gate: the
 * field stays populated until the SAF artifact validates, so the cleanup
 * predicate cannot delete the segments first.
 *
 * Failure classification (ADR-0024 §failure classification):
 *  - PERMANENT — `isPermissionHeld()` is false, OR the transient-retry
 *    budget (3) is exhausted → [ExportResult.SafFolderUnavailable] +
 *    `setFailed`. Surfaces WarningId.SAVE_FOLDER_UNAVAILABLE.
 *  - TRANSIENT — a throw while the permission is still held →
 *    MuxFailed / CopyFailed, manifest left retained (no terminal write),
 *    retried next cold launch (`incrementRetry`).
 *
 * Pure: every Android op (`createDocument`, `copyFileToDocument`,
 * `validateDocument`, `deleteDocument`, `isPermissionHeld`,
 * `displayNameOf`) is an injected lambda. Production wires them to
 * [SafAndroidOps].
 */
internal class SafExporter(
    private val treeUri: String,
    private val displayName: String,
    private val privateTempFile: File,
    private val setSafPrivateTemp: suspend (privateTempPath: String) -> ExportMutationResult,
    private val setSafTarget: suspend (docUri: String) -> ExportMutationResult,
    private val setFinalizedClear: suspend () -> ExportMutationResult,
    private val setFailed: suspend () -> ExportMutationResult,
    private val incrementRetry: suspend () -> ExportMutationResult,
    private val currentRetryCount: () -> Int,
    private val mux: suspend (segments: List<File>, output: File) -> Unit,
    private val createDocument: (displayName: String) -> String?,
    private val displayNameOf: (docUri: String) -> String?,
    private val copyFileToDocument: (src: File, docUri: String) -> Unit,
    private val validateDocument: (docUri: String) -> Boolean,
    private val deleteDocument: (docUri: String) -> Boolean,
    private val isPermissionHeld: () -> Boolean
) {
    private companion object { const val TAG = "SafExporter"; const val RETRY_BUDGET = 3 }

    /** Live export. [segments] mux to the private temp, then publish to SAF. */
    suspend fun export(sessionId: String): ExportResult = exportWith(sessionId, segmentsKnown = null)

    suspend fun exportSegments(sessionId: String, segments: List<File>): ExportResult =
        exportWith(sessionId, segmentsKnown = segments)

    private suspend fun exportWith(sessionId: String, segmentsKnown: List<File>?): ExportResult {
        if (!isPermissionHeld()) return permanent(sessionId, cause = null)

        // 1. Commit private temp BEFORE mux (MUXING).
        whenFailed(setSafPrivateTemp(privateTempFile.absolutePath)) { return it }

        // 2. Mux to local temp (always seekable).
        privateTempFile.parentFile?.mkdirs()
        try {
            mux(segmentsKnown ?: emptyList(), privateTempFile)
        } catch (t: Throwable) {
            RovaLog.w("$TAG: mux failed for $sessionId", t)
            cleanup(sessionId); return ExportResult.MuxFailed(t)
        }

        // 3. Publish to SAF (create doc → commit → copy → validate → finalize).
        return publish(sessionId, privateTempFile)
    }

    /** Recovery — validate-before-delete (ADR-0024 §validate-before-delete). */
    suspend fun recover(manifest: SessionManifest): RecoveryResult {
        val sessionId = manifest.sessionId

        // 1. A committed SAF doc that already validates is the GOOD artifact
        //    (finalize write was lost). Never re-copy/delete it.
        val docUri = manifest.safTargetDocUri
        if (docUri != null && validateDocument(docUri)) {
            return when (val r = setFinalizedClear()) {
                is ExportMutationResult.Wrote -> RecoveryResult.Resumed(
                    ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false))
                is ExportMutationResult.UnknownSession -> RecoveryResult.UnknownSession(r.sessionId)
                is ExportMutationResult.Failed -> RecoveryResult.ManifestWriteFailed(r.cause)
            }
        }

        if (!isPermissionHeld() || currentRetryCount() >= RETRY_BUDGET) {
            return RecoveryResult.Resumed(permanent(sessionId, cause = null))
        }

        // 2. Re-copy from a retained temp, or re-mux if the temp is gone.
        val temp = manifest.privateTempPath?.let(::File)
        if (temp != null && temp.exists() && temp.length() > 0L) {
            return RecoveryResult.Resumed(publish(sessionId, temp))
        }

        // 3. Nothing retained — transient: count and defer.
        incrementRetry()
        return RecoveryResult.RetryableFailure("saf-recover-no-temp",
            IllegalStateException("no private temp to resume SAF publish for $sessionId"))
    }

    // ─── shared publish ───────────────────────────────────────────────
    private suspend fun publish(sessionId: String, temp: File): ExportResult {
        val docUri: String = try {
            createDocument(displayName) ?: return classify(sessionId, cause = null)
        } catch (t: Throwable) {
            return classify(sessionId, cause = t)
        }
        // Capture the provider-authoritative name (may auto-rename).
        displayNameOf(docUri)?.let { RovaLog.d("$TAG: created doc as '$it'") }

        // Commit BEFORE the first byte (commit-before-stream invariant).
        whenFailed(setSafTarget(docUri)) { return it }

        try {
            copyFileToDocument(temp, docUri)
        } catch (t: Throwable) {
            RovaLog.w("$TAG: copy-to-SAF failed for $sessionId", t)
            safeDelete(docUri)
            return classify(sessionId, cause = t)
        }
        if (!validateDocument(docUri)) {
            RovaLog.w("$TAG: SAF doc failed validation for $sessionId")
            safeDelete(docUri)
            return classify(sessionId, cause = null)
        }
        return when (val r = setFinalizedClear()) {
            is ExportMutationResult.Wrote ->
                ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
            is ExportMutationResult.UnknownSession -> ExportResult.UnknownSession(r.sessionId)
            is ExportMutationResult.Failed -> ExportResult.ManifestWriteFailed("setExportFinalized", r.cause)
        }
    }

    /** Permanent vs transient classification on a publish failure. */
    private suspend fun classify(sessionId: String, cause: Throwable?): ExportResult =
        if (!isPermissionHeld() || currentRetryCount() >= RETRY_BUDGET) permanent(sessionId, cause)
        else { incrementRetry(); ExportResult.CopyFailed(cause ?: java.io.IOException("SAF publish failed")) }

    private suspend fun permanent(sessionId: String, cause: Throwable?): ExportResult {
        when (val r = setFailed()) {
            is ExportMutationResult.Wrote -> {}
            is ExportMutationResult.UnknownSession -> return ExportResult.UnknownSession(r.sessionId)
            is ExportMutationResult.Failed -> return ExportResult.ManifestWriteFailed("setExportFailed", r.cause)
        }
        return ExportResult.SafFolderUnavailable(cause)
    }

    private suspend fun cleanup(sessionId: String) {
        when (val r = setFailed()) {
            is ExportMutationResult.Wrote -> {}
            else -> RovaLog.w("$TAG: setFailed did not land for $sessionId")
        }
    }

    private fun safeDelete(docUri: String) {
        try { deleteDocument(docUri) } catch (t: Throwable) { RovaLog.w("$TAG: deleteDocument threw", t) }
    }

    private inline fun whenFailed(r: ExportMutationResult, onFail: (ExportResult) -> Unit) {
        when (r) {
            is ExportMutationResult.Wrote -> {}
            is ExportMutationResult.UnknownSession -> onFail(ExportResult.UnknownSession(r.sessionId))
            is ExportMutationResult.Failed -> onFail(ExportResult.ManifestWriteFailed("saf-commit", r.cause))
        }
    }
}
```

> The test's `exporter(...)` uses `export("s")` with the default mux writing bytes; for clarity the production call path (Task 5) uses `exportSegments(sessionId, segments)`. Keep both entry points. Adjust the test's `export` calls to `exportSegments("s", emptyList())` if you prefer a single entry — either compiles; the plan's tests above call `export("s")`, so keep the no-arg `export` delegating to `exportWith(sessionId, null)`.

- [ ] **Step 5: Run the test — iterate to PASS** (all cases).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/export/SafExporter.kt app/src/main/java/com/aritr/rova/service/export/ExportTypes.kt app/src/test/java/com/aritr/rova/service/export/SafExporterTest.kt
git commit -m "feat(b4): SafExporter — preQ-rails SAF publish, validate-before-delete recovery, failure classification"
```

---

## Task 5: Wire the route — ExportTier.SAF_DESTINATION + dispatch + recovery + validator

This task adds the enum value and fixes **every** exhaustive `when` the compiler surfaces. Build stays green only after all are handled.

**Files:**
- Modify: `data/SessionManifest.kt`, `service/export/ExportPipeline.kt`, `service/export/TierArtifactValidator.kt`, `RovaApp.kt`
- Test: `app/src/test/java/com/aritr/rova/data/CurrentExportTierSafTest.kt`, `app/src/test/java/com/aritr/rova/service/export/TierArtifactValidatorSafTest.kt`

- [ ] **Step 1: Write the failing tests**

`CurrentExportTierSafTest.kt`:
```kotlin
package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentExportTierSafTest {
    @Test fun safFolder_forces_SAF_tier_regardless_of_sdk() {
        assertEquals(ExportTier.SAF_DESTINATION, currentExportTier(hasUsableSafFolder = true))
    }
    @Test fun noSafFolder_uses_sdk_tier() {
        // On the JVM test runtime SDK_INT is 0 → TIER3 branch.
        assertEquals(currentExportTier(), currentExportTier(hasUsableSafFolder = false))
    }
}
```

`TierArtifactValidatorSafTest.kt`:
```kotlin
package com.aritr.rova.service.export

import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TierArtifactValidatorSafTest {
    private fun m(doc: String?) = SessionManifest("s", 0, SessionConfig(1,1,"x",1,"Portrait"),
        emptyList(), ExportTier.SAF_DESTINATION, safTargetDocUri = doc)

    @Test fun saf_valid_when_probe_true() {
        assertTrue(TierArtifactValidator.isArtifactValid(m("content://d"), tier1Probe = { false }, safProbe = { true }))
    }
    @Test fun saf_invalid_when_doc_null() {
        assertFalse(TierArtifactValidator.isArtifactValid(m(null), tier1Probe = { false }, safProbe = { true }))
    }
}
```

- [ ] **Step 2: Run them — confirm FAIL.**

- [ ] **Step 3: Add the enum value** (`SessionManifest.kt`)

```kotlin
enum class ExportTier {
    TIER1_API29_PLUS,
    TIER2_API26_28,
    TIER3_API24_25,
    SAF_DESTINATION   // ADR-0024 — setting-derived route; API-orthogonal. "Tier" now means "export route".
}
```

- [ ] **Step 4: Add the SAF-aware overload** (`SessionManifest.kt`)

```kotlin
/** ADR-0024 — route selection. A usable custom SAF folder wins over the SDK tier. */
fun currentExportTier(hasUsableSafFolder: Boolean): ExportTier =
    if (hasUsableSafFolder) ExportTier.SAF_DESTINATION else currentExportTier()
```

(Leave the existing no-arg `currentExportTier()` as the SDK-only helper.)

- [ ] **Step 5: Fix `ExportPipeline.exportPreQ`'s `when`** — add the guard:

```kotlin
            ExportTier.TIER1_API29_PLUS -> error("exportPreQ called with TIER1")
            ExportTier.SAF_DESTINATION -> error("exportPreQ called with SAF_DESTINATION")
```

- [ ] **Step 6: Add the SAF branch to `ExportPipeline.export()`** — in the top-level `when (val tier = currentExportTier())`, change it to honor the **frozen** tier and add the SAF arm. Replace the dispatch with:

```kotlin
        return when (val tier = frozenTier ?: currentExportTier()) {
            ExportTier.SAF_DESTINATION -> exportSaf(
                context = context, sessionStore = sessionStore, sessionId = sessionId,
                sessionDir = sessionDir, segments = segments, displayName = displayName,
                side = side, onProgress = onProgress
            )
            ExportTier.TIER1_API29_PLUS -> { /* ...unchanged... */ }
            ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 -> { /* ...unchanged... */ }
        }
```

Add a `frozenTier: ExportTier? = null` parameter to `export(...)` (default null preserves all existing callers). `RovaRecordingService` (Task 6) passes the session's frozen `manifest.exportTier`.

Add the private `exportSaf`:

```kotlin
    private suspend fun exportSaf(
        context: Context,
        sessionStore: SessionStore,
        sessionId: String,
        sessionDir: File,
        segments: List<File>,
        displayName: String,
        side: com.aritr.rova.service.dualrecord.VideoSide?,
        onProgress: (Float) -> Unit
    ): ExportResult {
        val settings = com.aritr.rova.data.RovaSettings(context)
        val treeUri = settings.saveLocationTreeUri
            ?: return ExportResult.SafFolderUnavailable(null)   // setting cleared mid-flight
        val resolver = context.contentResolver
        val privateTemp = File(sessionDir, "$displayName.private")
        val exporter = SafExporter(
            treeUri = treeUri,
            displayName = displayName,
            privateTempFile = privateTemp,
            setSafPrivateTemp = { p ->
                if (side == null) sessionStore.setExportSafPrivateTemp(sessionId, p)
                else sessionStore.setExportSafPrivateTempForSide(sessionId, side, p)
            },
            setSafTarget = { d ->
                if (side == null) sessionStore.setExportSafTarget(sessionId, d)
                else sessionStore.setExportSafTargetForSide(sessionId, side, d)
            },
            setFinalizedClear = {
                if (side == null) sessionStore.setExportFinalized(sessionId, clearPrivateTempPath = true)
                else sessionStore.setExportFinalizedForSide(sessionId, side, publicTargetPath = "", clearPrivateTempPath = true)
            },
            setFailed = { sessionStore.setExportFailed(sessionId) },
            incrementRetry = { sessionStore.incrementSafTransientRetry(sessionId) },
            currentRetryCount = { sessionStore.loadManifest(sessionId)?.safTransientRetryCount ?: 0 },
            mux = { segs, out -> VideoMerger.mergeSegments(segs, out, onProgress) },
            createDocument = { name -> SafAndroidOps.createDocument(resolver, treeUri, name) },
            displayNameOf = { d -> SafAndroidOps.displayNameOf(resolver, d) },
            copyFileToDocument = { src, d -> SafAndroidOps.copyFileToDocument(resolver, src, d) },
            validateDocument = { d -> SafAndroidOps.validateDocument(resolver, d) },
            deleteDocument = { d -> SafAndroidOps.deleteDocument(resolver, d) },
            isPermissionHeld = { SafAndroidOps.isPersistedPermissionHeld(resolver, treeUri) }
        )
        return exporter.exportSegments(sessionId, segments)
    }
```

> `VideoMerger.mergeSegments` is called inside `service/export/` here, satisfying `checkExportPipelineSingleEntry` invariant 2. The for-side `setExportFinalizedForSide(..., publicTargetPath = "")` passes an empty public path because for SAF the per-side artifact reference is `portraitSafTargetDocUri`; the TierArtifactValidator P+L SAF branch (Step 8) reads the SAF field, not `publicTargetPath`.

- [ ] **Step 7: Honor the frozen tier in recovery dispatch** — `RovaApp` `tierRecover` `when (m.exportTier)`: add

```kotlin
                ExportTier.SAF_DESTINATION -> buildSafRecover(m, s)
```

and add a private helper in `RovaApp` that builds a `SafExporter` for recovery (mirrors `exportSaf` but with `mux` re-muxing from segments via `sessionStore.sessionDir(m.sessionId)` segment listing, and calls `.recover(m)`):

```kotlin
        fun buildSafRecover(m: SessionManifest, s: com.aritr.rova.service.dualrecord.VideoSide?):
            suspend () -> RecoveryResult = {
            val settings = RovaSettings(this)
            val treeUri = settings.saveLocationTreeUri
            if (treeUri == null) {
                RecoveryResult.RetryableFailure("saf-no-tree",
                    IllegalStateException("SAF manifest but no saved tree uri"))
            } else {
                val resolver = contentResolver
                val sessionDir = sessionStore.sessionDir(m.sessionId)
                val displayName = (s?.let { side ->
                    if (side == com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT) m.portraitSafTargetDocUri
                    else m.landscapeSafTargetDocUri
                } ?: m.safTargetDocUri)?.substringAfterLast('/') ?: "Rova_recovered.mp4"
                val privateTemp = File(sessionDir, "$displayName.private")
                com.aritr.rova.service.export.SafExporter(
                    treeUri = treeUri, displayName = displayName, privateTempFile = privateTemp,
                    setSafPrivateTemp = { p -> if (s == null) sessionStore.setExportSafPrivateTemp(m.sessionId, p) else sessionStore.setExportSafPrivateTempForSide(m.sessionId, s, p) },
                    setSafTarget = { d -> if (s == null) sessionStore.setExportSafTarget(m.sessionId, d) else sessionStore.setExportSafTargetForSide(m.sessionId, s, d) },
                    setFinalizedClear = { if (s == null) sessionStore.setExportFinalized(m.sessionId, true) else sessionStore.setExportFinalizedForSide(m.sessionId, s, "", true) },
                    setFailed = { sessionStore.setExportFailed(m.sessionId) },
                    incrementRetry = { sessionStore.incrementSafTransientRetry(m.sessionId) },
                    currentRetryCount = { sessionStore.loadManifest(m.sessionId)?.safTransientRetryCount ?: 0 },
                    mux = { _, out -> com.aritr.rova.utils.VideoMerger.mergeSegments(listSegments(sessionDir), out) {} },
                    createDocument = { name -> com.aritr.rova.service.export.SafAndroidOps.createDocument(resolver, treeUri, name) },
                    displayNameOf = { d -> com.aritr.rova.service.export.SafAndroidOps.displayNameOf(resolver, d) },
                    copyFileToDocument = { src, d -> com.aritr.rova.service.export.SafAndroidOps.copyFileToDocument(resolver, src, d) },
                    validateDocument = { d -> com.aritr.rova.service.export.SafAndroidOps.validateDocument(resolver, d) },
                    deleteDocument = { d -> com.aritr.rova.service.export.SafAndroidOps.deleteDocument(resolver, d) },
                    isPermissionHeld = { com.aritr.rova.service.export.SafAndroidOps.isPersistedPermissionHeld(resolver, treeUri) }
                ).recover(m)
            }
        }
```

> `listSegments(sessionDir)` — reuse the existing helper RovaApp/recovery already uses to enumerate `segment_NNNN.mp4` (grep `segment_` in RovaApp/recovery; if none is exposed, add a small private `fun listSegments(dir: File): List<File> = dir.listFiles { f -> f.name.matches(Regex("segment_\\d{4}\\.mp4")) }?.sortedBy { it.name } ?: emptyList()`). This keeps `mux` re-muxing from retained segments when the private temp is gone.

- [ ] **Step 8: Add `safProbe` to TierArtifactValidator** and the SAF branches:

Change the signature:
```kotlin
    fun isArtifactValid(
        manifest: SessionManifest,
        tier1Probe: (uriString: String) -> Boolean,
        safProbe: (uriString: String) -> Boolean = { false }
    ): Boolean {
```

P+L `when`:
```kotlin
                ExportTier.SAF_DESTINATION -> {
                    val p = manifest.portraitSafTargetDocUri?.let(safProbe) ?: false
                    val l = manifest.landscapeSafTargetDocUri?.let(safProbe) ?: false
                    p || l
                }
```

Single-mode `when`:
```kotlin
            ExportTier.SAF_DESTINATION -> manifest.safTargetDocUri?.let(safProbe) ?: false
```

Then wire it in `RovaApp`'s `validateTierArtifact` lambda:
```kotlin
        val safProbe: (String) -> Boolean = { uri ->
            com.aritr.rova.service.export.SafAndroidOps.validateDocument(contentResolver, uri)
        }
        val validateTierArtifact: suspend (SessionManifest) -> Boolean = { m ->
            com.aritr.rova.service.export.TierArtifactValidator.isArtifactValid(m, tier1Probe, safProbe)
        }
```

- [ ] **Step 9: Fix any remaining compile errors** the compiler surfaces from the new enum value or `ExportResult.SafFolderUnavailable` (e.g. `RovaRecordingService.estimatePeakBytes(tier)` — add `ExportTier.SAF_DESTINATION -> <same sizing as TIER2/3>` since SAF muxes a local temp of identical size; any `when (result)` over `ExportResult` — handle `SafFolderUnavailable` as a non-terminal failure, no `COMPLETED`). Handle the result-mapping in `RovaRecordingService.performMerge` in Task 6.

- [ ] **Step 10: Run both new tests + a full compile**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.data.CurrentExportTierSafTest" --tests "com.aritr.rova.service.export.TierArtifactValidatorSafTest"`
Then: `gradlew.bat :app:compileDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL. Also re-run existing `TierArtifactValidatorTest` and `ExportRecoveryRunnerTest` — they must still pass (the new `safProbe` param defaults to `{ false }`).

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat(b4): wire SAF_DESTINATION route — dispatch, frozen-tier honor, validator+recovery safProbe"
```

---

## Task 6: Freeze SAF at session start (service + settings + createSession)

**Files:**
- Modify: `data/RovaSettings.kt`, `data/SessionStore.kt`, `service/RovaRecordingService.kt`

- [ ] **Step 1: Add the settings (backed-up prefs, null-aware)** — `RovaSettings.kt`, mirroring `localeTag`:

```kotlin
    var saveLocationTreeUri: String?
        get() = prefs.getString("save_location_tree_uri", null)
        set(value) = prefs.edit {
            if (value == null) remove("save_location_tree_uri") else putString("save_location_tree_uri", value)
        }

    var saveLocationLabel: String?
        get() = prefs.getString("save_location_label", null)
        set(value) = prefs.edit {
            if (value == null) remove("save_location_label") else putString("save_location_label", value)
        }
```

- [ ] **Step 2: Add the SAF-folder freeze param to `createSession`** — `SessionStore.kt` L110:

```kotlin
    fun createSession(
        config: SessionConfig,
        audioMode: AudioMode = AudioMode.VIDEO_ONLY,
        hasUsableSafFolder: Boolean = false
    ): String {
```

and change L118:
```kotlin
        val tier = currentExportTier(hasUsableSafFolder)
```

(Default `false` keeps all existing callers + tests compiling and behaviorally unchanged.)

- [ ] **Step 3: Compute `hasUsableSafFolder` in the service and pass it** — `RovaRecordingService.kt`, where `sessionStore.createSession(...)` is called. Add a helper and pass the flag:

```kotlin
    private fun hasUsableSafFolder(): Boolean {
        val settings = com.aritr.rova.data.RovaSettings(this)
        val tree = settings.saveLocationTreeUri ?: return false
        // P+L is deferred for SAF this slice — fall back to SDK tier for P+L sessions.
        if (settings.mode == "PortraitLandscape") return false
        return com.aritr.rova.service.export.SafAndroidOps.isPersistedPermissionHeld(contentResolver, tree)
    }
```

At the `createSession(...)` call site:
```kotlin
        val sid = sessionStore.createSession(config, audioMode, hasUsableSafFolder = hasUsableSafFolder())
```

> **P+L scope note:** per the spec, P+L SAF support exists in the data/exporter layer (per-side fields + per-side setters), but session-start freeze is gated to single-mode this slice to avoid shipping a half-exercised P+L-SAF path. A P+L session with a custom folder uses the default tier (documented in ADR-0024 + CHANGELOG). The per-side plumbing is in place for a later P+L-SAF reveal.

- [ ] **Step 4: Pass the frozen tier into `ExportPipeline.export`** — `RovaRecordingService.runExportPipeline`, add `frozenTier = currentExportTier` to the call (the service already caches `currentExportTier: ExportTier?` from `m.exportTier`):

```kotlin
        return ExportPipeline.export(
            context = this@RovaRecordingService,
            sessionStore = sessionStore,
            sessionId = sessionId,
            sessionDir = sessionDir,
            segments = segments,
            frozenTier = currentExportTier,   // cached frozen value (RovaRecordingService field)
            side = side,
            onProgress = onProgress
        )
```

- [ ] **Step 5: Map `SafFolderUnavailable` in `performMerge`'s result dispatch** — wherever the `ExportResult` is consumed, add (no terminal `COMPLETED`; flag the unavailable folder for the warning):

```kotlin
            is ExportResult.SafFolderUnavailable -> {
                com.aritr.rova.data.RovaSettings(this).saveFolderUnavailable = true
                // no markTerminated(COMPLETED) — recording retained for re-pick/resume
            }
```

(Adds `saveFolderUnavailable` runtime pref — see Task 8 Step 1. If Task 8 is implemented after this, temporarily set the flag via the pref added here and move the property to Task 8; simplest is to add the `saveFolderUnavailable` property in this step.)

- [ ] **Step 6: Compile + run existing service/store tests**

Run: `gradlew.bat :app:compileDebugKotlin` then `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.data.SessionStore*"`
Expected: BUILD SUCCESSFUL; existing SessionStore tests green (default `hasUsableSafFolder=false`).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(b4): freeze SAF route at session start (settings + createSession flag + frozen-tier passthrough)"
```

---

## Task 7: Settings UI — Save-location row + SAF folder picker

**Files:**
- Modify: `ui/screens/SettingsViewModel.kt`, `ui/screens/SettingsScreen.kt`
- Strings: `app/src/main/res/values/strings.xml` (+ `values-es/strings.xml` for parity — Spanish catalog exists; add the same keys or the `SpanishCatalogParityTest` fails).

- [ ] **Step 1: Add strings** (`values/strings.xml`)

```xml
    <string name="settings_save_location_label">Save location</string>
    <string name="settings_save_location_internal">Internal storage (default)</string>
    <string name="settings_save_location_choose">Choose folder…</string>
    <string name="settings_save_location_use_internal">Use internal storage</string>
    <string name="settings_save_location_unusable">That folder can\'t be used for saving. Pick another.</string>
    <string name="settings_section_storage">Storage</string>
```

And the Spanish parity entries in `values-es/strings.xml`:
```xml
    <string name="settings_save_location_label">Ubicación de guardado</string>
    <string name="settings_save_location_internal">Almacenamiento interno (predeterminado)</string>
    <string name="settings_save_location_choose">Elegir carpeta…</string>
    <string name="settings_save_location_use_internal">Usar almacenamiento interno</string>
    <string name="settings_save_location_unusable">Esa carpeta no se puede usar para guardar. Elige otra.</string>
    <string name="settings_section_storage">Almacenamiento</string>
```

- [ ] **Step 2: ViewModel state + setters** (`SettingsViewModel.kt`)

```kotlin
    val saveLocationLabel = MutableStateFlow(settings.saveLocationLabel)

    fun setSaveLocationFolder(treeUri: String, label: String) {
        settings.saveLocationTreeUri = treeUri
        settings.saveLocationLabel = label
        settings.saveFolderUnavailable = false   // re-pick clears the warning
        saveLocationLabel.value = label
    }

    fun clearSaveLocationFolder() {
        settings.saveLocationTreeUri = null
        settings.saveLocationLabel = null
        settings.saveFolderUnavailable = false
        saveLocationLabel.value = null
    }
```

Add to `reloadRecordingDefaults()` (ON_RESUME reseed): `saveLocationLabel.value = settings.saveLocationLabel`.

- [ ] **Step 3: SettingsScreen — Storage section with the SAF picker**

```kotlin
            val saveLabel by viewModel.saveLocationLabel.collectAsStateWithLifecycle()
            val unusableMsg = stringResource(R.string.settings_save_location_unusable)
            val pickFolder = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    val resolver = context.contentResolver
                    try {
                        resolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: SecurityException) { /* fall through to probe failure */ }
                    val ok = com.aritr.rova.service.export.SafAndroidOps.writeProbe(resolver, uri.toString())
                    if (ok) {
                        val label = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
                            ?: uri.toString()
                        viewModel.setSaveLocationFolder(uri.toString(), label)
                    } else {
                        Toast.makeText(context, unusableMsg, Toast.LENGTH_LONG).show()
                    }
                }
            }

            SettingsSection(label = stringResource(R.string.settings_section_storage)) {
                SettingsRow(
                    icon = Icons.Default.Folder,
                    label = stringResource(R.string.settings_save_location_label),
                    value = saveLabel ?: stringResource(R.string.settings_save_location_internal),
                    onClick = { pickFolder.launch(null) },
                    trailing = { ChevronTrailing() }
                )
                if (saveLabel != null) {
                    SettingsRow(
                        icon = Icons.Default.SettingsBackupRestore,
                        label = stringResource(R.string.settings_save_location_use_internal),
                        onClick = { viewModel.clearSaveLocationFolder() }
                    )
                }
            }
```

Add imports: `androidx.activity.compose.rememberLauncherForActivityResult`, `androidx.activity.result.contract.ActivityResultContracts`, `androidx.compose.material.icons.filled.Folder`, `androidx.compose.material.icons.filled.SettingsBackupRestore`. (`Icons.Default.Folder`/`SettingsBackupRestore` are in `material-icons-extended`, already a dep.)

- [ ] **Step 4: Compile + run the Spanish parity test**

Run: `gradlew.bat :app:compileDebugKotlin` then `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.locale.SpanishCatalogParityTest"`
Expected: BUILD SUCCESSFUL; parity green (new keys present in both catalogs).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(b4): Settings Save-location row + SAF folder picker (probe + persist permission)"
```

---

## Task 8: WarningId.SAVE_FOLDER_UNAVAILABLE + signal + precedence

**Files:**
- Modify: `data/RovaSettings.kt`, `ui/warnings/WarningId.kt`, `ui/warnings/WarningPrecedence.kt`, `ui/warnings/WarningCenter.kt`, `ui/warnings/WarningCenterViewModel.kt`, `RovaApp.kt`
- Create: `ui/signals/SaveFolderSignal.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceSaveFolderTest.kt`; update `WarningIdOrderTest`.

- [ ] **Step 1: Runtime pref** (`RovaSettings.kt`, on `runtimePrefs` — a transient per-install flag)

```kotlin
    var saveFolderUnavailable: Boolean
        get() = runtimePrefs.getBoolean("save_folder_unavailable", false)
        set(value) = runtimePrefs.edit { putBoolean("save_folder_unavailable", value) }
```

- [ ] **Step 2: Add the WarningId** (append at the end so ordinals of existing ids don't shift; ADVISORY)

```kotlin
    THERMAL_MODERATE(WarningTier.ADVISORY),             // #19
    NOTIFICATIONS_DENIED(WarningTier.ADVISORY),         // #20
    SAVE_FOLDER_UNAVAILABLE(WarningTier.ADVISORY)       // #21 ← B4b (ADR-0024) custom save folder unusable
}
```

- [ ] **Step 3: Update `WarningIdOrderTest`** — bump the expected count to 21 and assert `SAVE_FOLDER_UNAVAILABLE` is last (ordinal 20). (Open the test, change `assertEquals(20, WarningId.values().size)` → `21`, add the ordinal assertion.)

- [ ] **Step 4: The signal** (`ui/signals/SaveFolderSignal.kt`, mirrors `ExactAlarmSignal`)

```kotlin
package com.aritr.rova.ui.signals

import android.content.Context
import com.aritr.rova.data.RovaSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** B4b (ADR-0024). True iff a custom save folder was flagged permanently unavailable. */
class SaveFolderSignal(private val isUnavailable: () -> Boolean) {
    private val _state = MutableStateFlow(isUnavailable())
    val state: StateFlow<Boolean> = _state.asStateFlow()
    fun refresh() { _state.value = isUnavailable() }

    companion object {
        fun forContext(context: Context): SaveFolderSignal {
            val appCtx = context.applicationContext
            return SaveFolderSignal { RovaSettings(appCtx).saveFolderUnavailable }
        }
    }
}
```

- [ ] **Step 5: Lazy in RovaApp**

```kotlin
    val saveFolderSignal: SaveFolderSignal by lazy { SaveFolderSignal.forContext(this) }
```

- [ ] **Step 6: Thread through WarningPrecedence** — add a `saveFolderUnavailable: Boolean` param to `resolve(...)` and `allActive(...)`, and append at the matching (last) position:

```kotlin
        if (saveFolderUnavailable) result += WarningId.SAVE_FOLDER_UNAVAILABLE   // #21
```

- [ ] **Step 7: Write the failing precedence test**

```kotlin
package com.aritr.rova.ui.warnings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WarningPrecedenceSaveFolderTest {
    @Test fun saveFolderUnavailable_surfaces_when_nothing_higher() {
        val id = WarningPrecedence.resolve(
            /* set all other conditions to their "no warning" value, saveFolderUnavailable = true */
        )
        assertEquals(WarningId.SAVE_FOLDER_UNAVAILABLE, id)
    }
    @Test fun absent_when_flag_false() {
        val id = WarningPrecedence.resolve(/* all clear, saveFolderUnavailable = false */)
        assertNull(id)
    }
}
```

> Fill the `resolve(...)` argument list to match the real signature (the other args set to their non-warning values). Mirror the existing `WarningPrecedenceTest` call style.

- [ ] **Step 8: Wire the VM** — in `buildWarningCenterViewModel` add `saveFolderUnavailable = app.saveFolderSignal.state`, and add the corresponding `StateFlow<Boolean>` param to `WarningCenterViewModel` + include it in its `resolve(...)` combine.

- [ ] **Step 9: Refresh the signal** where other signals are refreshed (the same `onResume`/refresh site in RovaApp/MainActivity that calls `exactAlarmSignal.refresh()`), add `saveFolderSignal.refresh()`.

- [ ] **Step 10: Run the new + ordinal tests, then compile**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningPrecedenceSaveFolderTest" --tests "com.aritr.rova.ui.warnings.WarningIdOrderTest"`
Then `gradlew.bat :app:compileDebugKotlin`.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat(b4): SAVE_FOLDER_UNAVAILABLE warning + SaveFolderSignal + precedence wiring"
```

---

## Task 9: Static-check gate — checkSafTargetCommittedBeforeStream

Enforce: in `service/export/`, an `openOutputStream(`/`copyFileToDocument(` against the SAF target is preceded by a `setExportSafTarget`/`setSafTarget` reference in the same file. Line-oriented tripwire (mirrors the existing export gates).

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Register the task** (next to the other `checkExport*` tasks)

```kotlin
val checkSafTargetCommittedBeforeStream = tasks.register("checkSafTargetCommittedBeforeStream") {
    group = "verification"
    description = "SAF openOutputStream/copy must be preceded by setExportSafTarget commit (ADR-0024 §commit-before-stream)."
    val srcDir = file("src/main/java/com/aritr/rova/service/export")
    inputs.dir(srcDir).withPropertyName("exportSrc")
    doLast {
        if (!srcDir.exists()) throw GradleException("export source dir missing: $srcDir")
        val offenders = mutableListOf<String>()
        srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val lines = f.readLines()
            val streamIdx = lines.indexOfFirst {
                val t = it.trimStart()
                !t.startsWith("//") && !t.startsWith("*") &&
                    (it.contains("copyFileToDocument(") || it.contains("openOutputStream("))
            }
            if (streamIdx >= 0) {
                val commitsBefore = lines.take(streamIdx).any {
                    it.contains("setExportSafTarget") || it.contains("setSafTarget(")
                }
                // Only SafExporter.kt / ExportPipeline.kt carry the live SAF publish;
                // SafAndroidOps.kt holds the raw stream op and is exempt (it is the seam).
                if (!commitsBefore && f.name != "SafAndroidOps.kt") {
                    offenders += "${f.relativeTo(rootDir)}:${streamIdx + 1}: SAF stream op without a prior setExportSafTarget commit"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "ADR-0024 §commit-before-stream violation:\n" + offenders.joinToString("\n") { "  $it" } +
                    "\nThe SAF target doc Uri MUST be committed to the manifest before any byte is written to it."
            )
        }
    }
}
```

- [ ] **Step 2: Wire into preBuild** (in the `tasks.matching { it.name == "preBuild" }.configureEach { ... }` block)

```kotlin
        dependsOn(checkSafTargetCommittedBeforeStream)
```

- [ ] **Step 3: Run the gate (green)**

Run: `gradlew.bat :app:checkSafTargetCommittedBeforeStream`
Expected: BUILD SUCCESSFUL (SafExporter commits `setSafTarget` before `copyFileToDocument`).

- [ ] **Step 4: Self-test the gate catches a violation** — temporarily move the `setSafTarget(docUri)` line in `SafExporter.publish` to AFTER `copyFileToDocument`, re-run the gate, confirm it FAILS, then revert.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts
git commit -m "feat(b4): checkSafTargetCommittedBeforeStream gate (28th check, ADR-0024)"
```

---

## Task 10: ADR-0024 + CHANGELOG + full verification

**Files:**
- Create: `docs/adr/0024-saf-export-destination.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Write ADR-0024** — capture: tier⇒route reframe; SAF=preQ-mechanism+SAF-publish (muxer never on SAF); commit-before-stream invariant + the new gate; validate-before-delete recovery; permanent/transient classification (retry budget 3); P+L-SAF freeze deferred to a later slice (per-side plumbing present); no existing `check*` edited (28th added). Reference the spec. Mirror the structure of `docs/adr/0023-localization-locale-picker.md`.

- [ ] **Step 2: CHANGELOG** — under `[Unreleased] ▸ Added`:

```markdown
- Custom **Save location**: pick a folder (incl. SD card) via the system folder picker; finished recordings are exported there instead of the default Movies folder. Crash-safe (recordings are never lost — they resume on next launch). Picture-in-Picture (P+L) sessions still use the default location for now. (ADR-0024)
```

- [ ] **Step 3: Full suite + lint + assemble**

Run:
```
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:lintDebug
gradlew.bat :app:assembleDebug
```
Expected: all green; `lintDebug` runs all 28 `check*` gates + `MissingTranslation`/`ExtraTranslation` over `values-es/`; suite 0-0-0.

- [ ] **Step 4: Commit**

```bash
git add docs/adr/0024-saf-export-destination.md CHANGELOG.md
git commit -m "docs(b4): ADR-0024 SAF export destination + CHANGELOG"
```

---

## Self-review notes (author)

- **Spec coverage:** §1 settings+UI → T6/T7; §2 route model → T5/T6; §3 export mechanism → T4/T5; §4 recovery → T4(recover)/T5(wiring); §5 failure classification + edges → T4(classify)/T6(result map)/T8(warning); createDocument auto-rename → T1/T4; §6 ADR+gate → T9/T10; §7 testing → tests in T2–T5,T8 + T10 full run. **P+L:** per-side data/exporter/setters present (T3/T4/T5), but session freeze gated to single-mode (T6) — documented divergence (ADR + CHANGELOG), per the spec's "flag tight-fit / reduce footprint" latitude; per-side reveal is a clean follow-up.
- **Type consistency:** `setExportSafPrivateTemp`/`setExportSafTarget`/`incrementSafTransientRetry` (+`*ForSide`) consistent across T3/T4/T5; `currentExportTier(hasUsableSafFolder)` overload consistent T5/T6; `ExportResult.SafFolderUnavailable` consistent T4/T5/T6; `isArtifactValid(..., safProbe)` consistent T5; `saveLocationTreeUri`/`saveLocationLabel`/`saveFolderUnavailable` consistent T6/T7/T8.
- **Exhaustiveness audit:** the enum value (T5 Step 3) and the `ExportResult` variant (T4 Step 1) deliberately break every `when` — T5 Step 9 instructs fixing each compiler-surfaced site (`ExportPipeline.export`, `exportPreQ`, `TierArtifactValidator` ×2, `RovaApp.tierRecover`, `estimatePeakBytes`, `performMerge` result dispatch, any `ExportResult` signal mapper).
- **Gotchas honored:** SAF mux never touches a content FD (always local temp); `privateTempPath` retention reused (no cleanup-predicate edit, `checkExportCleanupPredicate` stays green); `VideoMerger` mux callers stay under `service/export/` + the single `ExportPipeline.export(` site stays in `RovaRecordingService` (`checkExportPipelineSingleEntry` green); no `copyToPublicMovies` symbol (SAF uses `copyFileToDocument`).
```

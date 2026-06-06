package com.aritr.rova.service.export

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.currentExportTier
import com.aritr.rova.utils.RovaLog
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException

/**
 * B5 / ADR-0025 (Task 21) — per-tier copy / delete(+rescan) / publish-existing
 * primitives that [VaultMover] dispatches when a recording is moved into or
 * out of the private vault. A thin Android seam (mirrors [Tier1AndroidOps] /
 * [SafAndroidOps]) — NOT unit-tested; verified by the Task 23 device smoke.
 *
 * Three dispatchers, each a `when (manifest.exportTier)`:
 *
 *  - [copyToPrivate]  (move-IN): read the published public bytes (Tier1 →
 *    `pendingUri`, Tier2/3 → `publicTargetPath`, SAF → `safTargetDocUri`)
 *    and write them to the session's app-private vault file. The vault file
 *    path is deterministic ([vaultFileFor]) so the call site can persist the
 *    same path via `SessionStore.setVaultState(VAULTING, path)`.
 *
 *  - [deletePublic]   (move-IN, after the private copy is committed): drop the
 *    public artifact. Tier1 → `resolver.delete(pendingUri)`; Tier2/3 → delete
 *    the file **then** rescan the now-missing path so the stale gallery index
 *    entry is dropped (codex review — a deleted file with a live MediaStore
 *    row reappears as a broken thumbnail until the next full scan); SAF →
 *    `DocumentFile.fromSingleUri(...).delete()`.
 *
 *  - [publishExisting] (move-OUT): copy the existing vault file's bytes back
 *    to public storage through the **same low-level publishers** the normal
 *    export uses — `Tier1AndroidOps.insertPendingRow` + `withPendingFd`
 *    (mode `"rw"`), the pre-Q `<name>.mp4.part` → `renameTo` atom +
 *    `MediaScanWaiter.scanAndWait`, or `SafAndroidOps.copyFileToDocument`.
 *    It supplies the already-merged vault file instead of segments, so
 *    `VideoMerger.mergeSegments*` is NEVER called — the single-entry gate
 *    `checkExportPipelineSingleEntry` is not tripped, and this file never
 *    re-enters `ExportPipeline.export`. The destination tier is recomputed
 *    **now** via `currentExportTier(hasUsableSafFolder())`, not the frozen
 *    `manifest.exportTier` (the published copy must land wherever the device
 *    can publish today).
 *
 * Why this is not flagged by `checkVaultExporterNoPublicPublish`: that gate
 * scans ONLY `VaultExporter.kt`. The publish-on-move-out path legitimately
 * uses MediaStore / scanFile-via-waiter, so it lives here, outside the
 * vault-exporter no-publish island.
 *
 * Construction mirrors the sibling seams: a single [Context], all framework
 * calls inline. Task 22 binds a [VaultMover]'s `copyToPrivate` /
 * `deletePublic` / `publishExisting` lambdas to a specific session by
 * capturing a [SessionManifest] + its `sessionDir` against this instance.
 */
internal class VaultAndroidOps(
    private val context: Context,
    private val mediaScanWaiter: MediaScanWaiter = AndroidMediaScanWaiter(context),
) {

    private val resolver get() = context.contentResolver

    /**
     * Deterministic app-private vault destination for a move-IN. Mirrors
     * [VaultExporter]'s convention (`File(sessionDir, displayName)`): the
     * vault file lives under the session dir using the public artifact's
     * display name. The call site passes the SAME path to
     * `SessionStore.setVaultState(VAULTING, path)` so the persisted pointer
     * and the on-disk file agree.
     */
    fun vaultFileFor(manifest: SessionManifest, sessionDir: File): File =
        File(sessionDir, vaultDisplayName(manifest))

    /**
     * Move-IN copy: published public bytes → app-private vault file.
     * Idempotent on the destination (overwrites). Throws on read/write
     * failure so the [VaultMover] ordering law keeps the public copy until
     * the private copy verifies.
     *
     * By design this copy runs BEFORE the VAULTING state is committed, and the
     * window is deliberately NON-destructive: the public copy is untouched
     * until [deletePublic], so a crash here leaves only a harmless,
     * re-copyable private file (no privacy exposure, nothing to reconcile).
     */
    suspend fun copyToPrivate(manifest: SessionManifest, sessionDir: File) {
        val dest = vaultFileFor(manifest, sessionDir)
        dest.parentFile?.mkdirs()
        when (manifest.exportTier) {
            ExportTier.TIER1_API29_PLUS -> {
                val uriString = manifest.pendingUri
                    ?: throw IOException("copyToPrivate: Tier1 manifest has null pendingUri")
                copyUriToFile(uriString, dest)
            }
            ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 -> {
                val src = manifest.publicTargetPath
                    ?: throw IOException("copyToPrivate: Tier2/3 manifest has null publicTargetPath")
                File(src).inputStream().use { input ->
                    FileOutputStream(dest).use { input.copyTo(it) }
                }
            }
            ExportTier.SAF_DESTINATION -> {
                val docUri = manifest.safTargetDocUri
                    ?: throw IOException("copyToPrivate: SAF manifest has null safTargetDocUri")
                copyUriToFile(docUri, dest)
            }
        }
    }

    /**
     * Move-IN delete: drop the public artifact once the private copy is
     * committed. **Fail-closed** for the primary delete: if a still-present
     * public artifact cannot be removed, this THROWS so the move stays in
     * VAULTING (a future recovery / move pass retries). This is a privacy
     * invariant — letting the move advance to VAULTED while the public copy
     * is still gallery-visible would mark the recording hidden in-app while
     * it remains visible on the device (codex review). "Already absent"
     * (resolver delete of an unknown row, `file.delete()==false` but the file
     * is gone, or a null/absent SAF doc) counts as SUCCESS, so a crash-resume
     * re-run that finds the artifact already removed does NOT throw. Only the
     * secondary MediaStore rescan remains best-effort.
     */
    suspend fun deletePublic(manifest: SessionManifest) {
        when (manifest.exportTier) {
            ExportTier.TIER1_API29_PLUS -> {
                val uriString = manifest.pendingUri
                if (uriString == null) {
                    RovaLog.w("deletePublic: Tier1 pendingUri null — nothing to delete")
                    return
                }
                // A 0-row delete = already gone = success (do NOT throw); only a
                // thrown exception is fatal.
                try {
                    resolver.delete(Uri.parse(uriString), null, null)
                } catch (t: Throwable) {
                    throw IOException("deletePublic: Tier1 resolver.delete failed for $uriString", t)
                }
            }
            ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 -> {
                val path = manifest.publicTargetPath
                if (path == null) {
                    RovaLog.w("deletePublic: Tier2/3 publicTargetPath null — nothing to delete")
                    return
                }
                val file = File(path)
                val deleted = try {
                    file.delete()
                } catch (t: Throwable) {
                    throw IOException("deletePublic: Tier2/3 file.delete failed for $path", t)
                }
                // Fail-closed: a real, still-present file we could not remove must NOT
                // let the move advance to VAULTED (privacy: public copy would stay in the
                // gallery). "Already absent" (delete()==false but file is gone) is success.
                if (!deleted && file.exists()) {
                    throw IOException("deletePublic: Tier2/3 could not remove public copy at $path")
                }
                // Rescan whenever the path is now gone (deleted OR already-absent) so a
                // stale MediaStore row / broken-thumbnail entry is evicted. Bounded,
                // timeout-harmless (checkScanFileBoundedWait), the only sanctioned scanFile.
                if (deleted || !file.exists()) {
                    mediaScanWaiter.scanAndWait(file)
                }
            }
            ExportTier.SAF_DESTINATION -> {
                val docUri = manifest.safTargetDocUri
                if (docUri == null) {
                    RovaLog.w("deletePublic: SAF safTargetDocUri null — nothing to delete")
                    return
                }
                val doc = DocumentFile.fromSingleUri(context, Uri.parse(docUri))
                val deleted = try {
                    doc?.delete() ?: false
                } catch (t: Throwable) {
                    throw IOException("deletePublic: SAF delete failed for $docUri", t)
                }
                // Already-gone (doc null / no longer exists) counts as success; only a
                // present doc we failed to delete is fatal.
                if (!deleted && doc?.exists() == true) {
                    throw IOException("deletePublic: SAF could not remove public doc $docUri")
                }
            }
        }
    }

    /**
     * Move-OUT publish: copy the existing vault file's bytes back to public
     * storage through the low-level publishers (NO mux, NO segments, NO
     * [ExportPipeline.export] re-entry). Destination tier is recomputed now.
     *
     * **Crash-window duplicate-copy: now guarded (ADR-0025 commit-before-
     * finalize).** All three tiers are crash-resume-deduplicated:
     * - SAF — [publishSaf] deletes the prior committed doc before re-creating
     *   (ADR-0024 commit-before-stream gives it a persisted pointer to clean up).
     * - Tier1 — [publishTier1] commits the freshly inserted pending-row Uri to
     *   the manifest ([setPendingMoveOutTier1], persisted as
     *   `pendingMoveOutUri`) BEFORE `withPendingFd`/`finalizePendingRow`. A
     *   crash after finalize but before [SessionStore.setVaultMovedOut] leaves
     *   that pointer; the resume drops the orphan row before re-publishing.
     * - pre-Q — [publishPreQ] commits the `<name>.mp4.part` path
     *   ([setPendingMoveOutPreQ], persisted as `pendingMoveOutPath`) BEFORE the
     *   first byte. The resume ([PreQMoveOutResume]) adopts an already-renamed
     *   target or deletes a stale `.part`, never minting a `<name>_2.mp4` dup.
     * Result: a move-out lands the recording in exactly one public place even
     * across a crash in the former ~millisecond window.
     *
     * @return the new public pointer for the call site to persist via
     *   `SessionStore.setVaultMovedOut(...)`, as a [PublishOutcome] carrying
     *   exactly one of `pendingUri` / `publicTargetPath` / `safTargetDocUri`.
     */
    suspend fun publishExisting(
        manifest: SessionManifest,
        sessionDir: File,
        setExportSafTarget: suspend (docUri: String) -> Unit,
        // ADR-0025 commit-before-finalize: persist the in-flight public pointer
        // BEFORE the irreversible publish step. Default no-ops keep older test
        // call sites compiling; the move-out builder wires the real setters.
        setPendingMoveOutTier1: suspend (pendingRowUri: String) -> Unit = {},
        setPendingMoveOutPreQ: suspend (partPath: String) -> Unit = {},
    ): PublishOutcome {
        val vaultPath = manifest.vaultFilePath
            ?: throw IOException("publishExisting: manifest has null vaultFilePath")
        val vaultFile = File(vaultPath)
        // Size guard: a zero-byte / truncated vault file must not reach a
        // terminal PUBLIC commit (it would publish an unplayable file).
        if (!vaultFile.isFile || vaultFile.length() == 0L) {
            throw IOException("publishExisting: vault file missing or empty at $vaultPath")
        }
        val displayName = vaultFile.name
        return when (currentExportTier(hasUsableSafFolder())) {
            ExportTier.TIER1_API29_PLUS -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    // Defensive: currentExportTier maps SDK_INT → tier, so a
                    // TIER1 result on pre-Q is impossible. Fall back to the
                    // pre-Q publisher rather than crash.
                    publishPreQ(displayName, vaultFile, manifest.pendingMoveOutPath, setPendingMoveOutPreQ)
                } else {
                    publishTier1(displayName, vaultFile, manifest.pendingMoveOutUri, setPendingMoveOutTier1)
                }
            }
            ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 ->
                publishPreQ(displayName, vaultFile, manifest.pendingMoveOutPath, setPendingMoveOutPreQ)
            ExportTier.SAF_DESTINATION ->
                publishSaf(displayName, vaultFile, manifest.safTargetDocUri, setExportSafTarget)
        }
    }

    // --- Tier1 publish (API 29+) -------------------------------------------

    private suspend fun publishTier1(
        displayName: String,
        src: File,
        priorMoveOutUri: String?,
        commitPendingMoveOut: suspend (String) -> Unit,
    ): PublishOutcome {
        // Crash-resume dedup: a committed pointer from a prior aborted run means
        // that run may have finalized a public row before its manifest pointer
        // landed. Drop that orphan before re-publishing so the recording ends up
        // in exactly one public place (ADR-0025 commit-before-finalize). A
        // `false` return means the row is already gone (fine). A THROW means a
        // real delete error — propagate so we ABORT rather than risk publishing a
        // second copy alongside an undeletable prior row (codex review).
        priorMoveOutUri?.let { Tier1AndroidOps.deletePendingRow(resolver, it) }
        val uriString = Tier1AndroidOps.insertPendingRow(resolver, displayName)
            ?: throw IOException("publishExisting: Tier1 insertPendingRow returned null")
        var finalized = false
        try {
            // Commit-before-finalize (mirrors ADR-0024 SAF commit-before-stream):
            // persist the pending Uri BEFORE withPendingFd / finalizePendingRow
            // make the public copy irreversible. Inside the try so a throwing
            // (mandatory) commit still drops the just-inserted pending row.
            commitPendingMoveOut(uriString)
            // Mode "rw" per ADR 0003 §FD Mode Amendment (checkPendingFdModeIsRW).
            Tier1AndroidOps.withPendingFd(resolver, uriString, "rw") { fd ->
                writeFileToFd(src, fd)
            }
            val result = Tier1AndroidOps.finalizePendingRow(resolver, uriString)
            if (result !is Tier1FinalizeResult.Finalized) {
                throw IOException("publishExisting: Tier1 finalize failed ($result) for $uriString")
            }
            finalized = true
            return PublishOutcome(pendingUri = uriString)
        } finally {
            if (!finalized) {
                // Drop the orphaned pending row so a failed move-out does not
                // leave an invisible half-published MediaStore entry.
                try {
                    Tier1AndroidOps.deletePendingRow(resolver, uriString)
                } catch (t: Throwable) {
                    RovaLog.w("publishExisting: Tier1 cleanup deletePendingRow threw for $uriString", t)
                }
            }
        }
    }

    // --- Tier2/3 publish (pre-Q direct path) -------------------------------

    private suspend fun publishPreQ(
        displayName: String,
        src: File,
        priorPartPath: String?,
        commitPendingMoveOut: suspend (String) -> Unit,
    ): PublishOutcome {
        @Suppress("DEPRECATION")
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "Rova"
        )
        publicDir.mkdirs()

        // Crash-resume dedup (ADR-0025 commit-before-finalize). A committed
        // `.part` pointer means a prior run got interrupted; the pure
        // [PreQMoveOutResume] decides whether its public copy already exists
        // (adopt it — no second file), or only a stale `.part` survives (drop
        // it, publish fresh). Without this, allocateNonColliding would mint a
        // `<name>_2.mp4` duplicate alongside the prior run's finished file.
        priorPartPath?.let { prior ->
            val priorTarget = File(prior.removeSuffix(".part"))
            val action = PreQMoveOutResume.decide(
                committedPartPath = prior,
                // Adopt ONLY a byte-exact copy of the vault file — a foreign file
                // that merely occupies the target path must not be claimed as
                // this session's public artifact (codex review).
                targetExistsAndAdoptable = priorTarget.isFile && priorTarget.length() == src.length(),
                partExists = File(prior).exists(),
            )
            when (action) {
                is PreQResumeAction.AdoptExistingTarget -> {
                    mediaScanWaiter.scanAndWait(priorTarget)
                    return PublishOutcome(publicTargetPath = action.targetPath)
                }
                is PreQResumeAction.DeleteStalePartThenProceed ->
                    try { File(action.partPath).delete() } catch (_: Throwable) {}
                PreQResumeAction.Proceed -> {}
            }
        }

        val target = allocateNonColliding(publicDir, displayName)
        val part = File(publicDir, target.name + ".part")
        // Commit-before-write: persist the `.part` path BEFORE the first byte so
        // a crash mid-copy is dedup-recoverable on the next cold launch.
        commitPendingMoveOut(part.absolutePath)
        try {
            src.inputStream().use { input ->
                FileOutputStream(part).use { input.copyTo(it) }
            }
            if (!part.renameTo(target)) {
                throw IOException("publishExisting: pre-Q renameTo failed ${part.absolutePath} -> ${target.absolutePath}")
            }
        } catch (t: Throwable) {
            try { part.delete() } catch (_: Throwable) {}
            throw t
        }
        // Index the freshly-published file. Bounded; timeout is non-fatal
        // (the gallery picks it up on the next full scan).
        mediaScanWaiter.scanAndWait(target)
        return PublishOutcome(publicTargetPath = target.absolutePath)
    }

    // --- SAF publish -------------------------------------------------------

    private suspend fun publishSaf(
        displayName: String,
        src: File,
        priorDocUri: String?,                       // manifest.safTargetDocUri, may be a stale/partial doc
        setExportSafTarget: suspend (docUri: String) -> Unit,
    ): PublishOutcome {
        val treeUri = RovaSettings(context).saveLocationTreeUri
            ?: throw IOException("publishExisting: SAF tier but saveLocationTreeUri is null")
        // Crash-resume orphan guard: a previously committed SAF target (e.g. a partial
        // doc from a publish that crashed mid-stream, or the stale pre-vault pointer)
        // must not survive as a second copy. Best-effort delete before re-creating so
        // the recording ends up in exactly one place (ADR-0025 move-out / Task 23 smoke).
        priorDocUri?.let {
            try { SafAndroidOps.deleteDocument(context, it) }
            catch (t: Throwable) { RovaLog.w("publishExisting: SAF prior-target cleanup failed for $it", t) }
        }
        val docUri = SafAndroidOps.createDocument(context, treeUri, displayName)
            ?: throw IOException("publishExisting: SAF createDocument returned null under $treeUri")
        try {
            setExportSafTarget(docUri)                          // ADR-0024 commit-before-stream
            SafAndroidOps.copyFileToDocument(context, src, docUri)
        } catch (t: Throwable) {
            try { SafAndroidOps.deleteDocument(context, docUri) } catch (_: Throwable) {}
            throw t
        }
        return PublishOutcome(safTargetDocUri = docUri)
    }

    // --- helpers -----------------------------------------------------------

    /** Read all bytes of [uriString] (content:// or document Uri) into [dest]. */
    private fun copyUriToFile(uriString: String, dest: File) {
        val input = resolver.openInputStream(Uri.parse(uriString))
            ?: throw IOException("copyToPrivate: openInputStream returned null for $uriString")
        input.use { src ->
            FileOutputStream(dest).use { src.copyTo(it) }
        }
    }

    /** Stream [src]'s bytes into an open (caller-owned) [fd]. */
    private fun writeFileToFd(src: File, fd: FileDescriptor) {
        src.inputStream().use { input ->
            FileOutputStream(fd).use { out -> input.copyTo(out) }
        }
    }

    /**
     * The display name to use for the vault file / republished public file.
     * Prefers an existing public pointer's name so a round-trip
     * (publish → vault → publish) keeps a stable filename; falls back to the
     * session id when no public name is on the manifest.
     */
    private fun vaultDisplayName(manifest: SessionManifest): String {
        manifest.publicTargetPath?.let { return File(it).name }
        manifest.safTargetDocUri?.let { uri ->
            SafAndroidOps.displayNameOf(context, uri)?.let { return it }
        }
        // Tier1: the pending row's display name is not cheaply available
        // without a MediaStore query — fall back to a deterministic
        // session-derived name (stable across a publish → vault round-trip).
        return "${manifest.sessionId}.mp4"
    }

    /**
     * Suffix `_2`, `_3`, … until the `<name>`, `<name>.part` pair is free in
     * [dir] (mirrors the [ExportPipeline] pre-Q collision probe so a
     * republish never clobbers an unrelated public file).
     */
    private fun allocateNonColliding(dir: File, displayName: String): File {
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var candidate = File(dir, displayName)
        var n = 2
        while (candidate.exists() || File(dir, candidate.name + ".part").exists()) {
            candidate = File(dir, "${base}_$n$ext")
            n++
            if (n > 9999) throw IllegalStateException("allocateNonColliding: suffix exhausted for $displayName")
        }
        return candidate
    }

    /**
     * Recompute SAF usability NOW (B4c parity with the service): a persisted
     * grant is not enough — the folder must still be writable, else fall back
     * to the SDK tier so the move-out still publishes somewhere.
     */
    private fun hasUsableSafFolder(): Boolean {
        val settings = RovaSettings(context)
        val tree = settings.saveLocationTreeUri ?: return false
        if (settings.mode == "PortraitLandscape") return false
        return SafAndroidOps.isTargetWritable(context, tree)
    }

    /**
     * Move-OUT publish result — exactly one pointer is non-null, matching the
     * destination tier. The call site feeds this to
     * `SessionStore.setVaultMovedOut(...)`.
     */
    data class PublishOutcome(
        val pendingUri: String? = null,
        val publicTargetPath: String? = null,
        val safTargetDocUri: String? = null,
    )
}

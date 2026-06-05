package com.aritr.rova.service.export

import java.io.File

/**
 * B5 / ADR-0025 — vault exporter. Merges segments straight to an
 * app-private file ([vaultFile]) and finalizes. Performs NO MediaStore
 * insert, NO MediaScannerConnection, NO public-path write, NO IS_PENDING —
 * a vaulted recording never becomes gallery-visible. Enforced by
 * `checkVaultExporterNoPublicPublish` (app/build.gradle.kts).
 *
 * Pure seam: all framework effects are injected lambdas so the success /
 * failure branches are JVM-testable (Tier2Exporter pattern).
 *
 * On success the vault file IS the finalized artifact (no public publish,
 * no scan, no separate private temp), so the returned
 * [ExportResult.Success] reports `mediaScanCompleted = false` (no scan ever
 * runs) and `privateTempRetained = false` (nothing left to reconcile).
 *
 * On a mux throw the generic post-mux retryable failure is
 * [ExportResult.MuxFailed] — there is no `ExportResult.RetryableFailure`
 * variant; `RetryableFailure` lives on [RecoveryResult]. This mirrors
 * Tier2Exporter / PreQExportCore, which map a mux throw to
 * [ExportResult.MuxFailed] after `setExportFailed`.
 */
internal class VaultExporter(
    private val vaultFile: File,
    private val mux: suspend (List<File>, File) -> Unit,
    private val setFinalized: suspend (String) -> Unit,
    private val setFailed: suspend () -> Unit,
) {
    suspend fun export(sessionId: String, segments: List<File>): ExportResult {
        return try {
            vaultFile.parentFile?.mkdirs()
            mux(segments, vaultFile)
            setFinalized(vaultFile.absolutePath)
            ExportResult.Success(
                mediaScanCompleted = false,
                privateTempRetained = false,
            )
        } catch (t: Throwable) {
            setFailed()
            ExportResult.MuxFailed(t)
        }
    }
}

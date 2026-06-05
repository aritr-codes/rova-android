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
            mux = { _, _ -> /* pretend merge wrote the file */ },
            setFinalized = { p -> finalizedPath = p },
            setFailed = { failed = true },
        )
        val result = exporter.export("s1", listOf(File("/seg/0.mp4")))
        assertTrue(result is ExportResult.Success)
        assertEquals(File("/tmp/vault/s1/Rova_x.mp4").absolutePath, finalizedPath)
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
        // No `ExportResult.RetryableFailure` exists — the generic post-mux
        // retryable failure variant is MuxFailed (mirrors Tier2/PreQExportCore).
        assertTrue(result is ExportResult.MuxFailed)
        assertEquals(false, finalized)
        assertEquals(true, failed)
    }
}

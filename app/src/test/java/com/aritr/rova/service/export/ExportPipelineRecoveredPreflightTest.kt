package com.aritr.rova.service.export

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ExportPipelineRecoveredPreflightTest {

    @Test
    fun `pre-flight returns InsufficientStorage when available bytes below required`() = runBlocking {
        // 100 KB segment × 1.05 = 105 KB required; only 50 KB available.
        val seg = File.createTempFile("seg", ".mp4").apply {
            writeBytes(ByteArray(100_000))
            deleteOnExit()
        }
        var muxCalled = false
        val result = ExportPipeline.exportRecoveredForTest(
            segments = listOf(seg),
            availableBytesProvider = { 50_000L },
            performMerge = { _, _ ->
                muxCalled = true
                ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
            },
            onProgress = {},
        )
        assertTrue(result is ExportResult.InsufficientStorage)
        result as ExportResult.InsufficientStorage
        assertEquals(105_000L, result.requiredBytes)
        assertEquals(50_000L, result.availableBytes)
        assertEquals(false, muxCalled)
    }

    @Test
    fun `pre-flight passes through to merge when storage sufficient`() = runBlocking {
        val seg = File.createTempFile("seg", ".mp4").apply {
            writeBytes(ByteArray(100_000))
            deleteOnExit()
        }
        var muxCalled = false
        val result = ExportPipeline.exportRecoveredForTest(
            segments = listOf(seg),
            availableBytesProvider = { 10_000_000L },
            performMerge = { _, _ ->
                muxCalled = true
                ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
            },
            onProgress = {},
        )
        assertTrue(result is ExportResult.Success)
        assertEquals(true, muxCalled)
    }
}

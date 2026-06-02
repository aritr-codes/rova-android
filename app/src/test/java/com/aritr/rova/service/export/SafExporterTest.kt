package com.aritr.rova.service.export

import com.aritr.rova.data.ExportMutationResult
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafExporterTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"))

    private fun wrote(): ExportMutationResult = ExportMutationResult.Wrote(
        SessionManifest("s", 0, SessionConfig(1, 1, "x", 1, "Portrait"),
            emptyList(), ExportTier.TIER2_API26_28)
    )

    private class Calls { val log = mutableListOf<String>() }

    private fun exporter(
        calls: Calls = Calls(),
        mux: suspend (List<File>, File) -> Unit = { _, out -> out.writeBytes(ByteArray(16)) },
        createDoc: (String) -> String? = { "content://tree/doc/1" },
        nameProbe: (String) -> String? = { "Rova.mp4" },
        copyToDoc: (File, String) -> Unit = { _, _ -> },
        validateDoc: (String) -> Boolean = { true },
        deleteDoc: (String) -> Boolean = { true },
        permissionHeld: () -> Boolean = { true },
        retryCount: Int = 0
    ) = SafExporter(
        displayName = "Rova.mp4",
        privateTempFile = File(tempDir, "saf_${System.nanoTime()}.private"),
        setSafPrivateTemp = { _ -> calls.log += "setSafPrivateTemp"; wrote() },
        setSafTarget = { _ -> calls.log += "setSafTarget"; wrote() },
        setFinalizedClear = { calls.log += "finalizedClear"; wrote() },
        setFailed = { calls.log += "setFailed"; wrote() },
        incrementRetry = { calls.log += "incrementRetry"; wrote() },
        currentRetryCount = { retryCount },
        mux = mux,
        createDocument = createDoc,
        displayNameOf = nameProbe,
        copyFileToDocument = copyToDoc,
        validateDocument = validateDoc,
        deleteDocument = deleteDoc,
        isPermissionHeld = permissionHeld
    )

    @Test fun happyPath_returns_Success_and_commits_target_before_finalize() = runBlocking {
        val calls = Calls()
        val r = exporter(calls).export("s", emptyList())
        assertTrue(r is ExportResult.Success)
        assertTrue("setSafPrivateTemp" in calls.log)
        assertTrue("setSafTarget" in calls.log)
        assertTrue("finalizedClear" in calls.log)
        // setSafTarget (commit-before-stream) precedes finalize
        assertTrue(calls.log.indexOf("setSafTarget") < calls.log.indexOf("finalizedClear"))
    }

    @Test fun muxFails_transient_returns_MuxFailed_and_bumps_retry_no_setFailed() = runBlocking {
        val calls = Calls()
        val r = exporter(calls, mux = { _, _ -> throw RuntimeException("mux") }).export("s", emptyList())
        assertTrue(r is ExportResult.MuxFailed)
        assertTrue("incrementRetry" in calls.log)
        assertTrue("setFailed" !in calls.log)   // transient must NOT mark FAILED
    }

    @Test fun copyFails_permissionHeld_is_transient_CopyFailed() = runBlocking {
        val r = exporter(copyToDoc = { _, _ -> throw java.io.IOException("busy") },
            permissionHeld = { true }).export("s", emptyList())
        assertTrue(r is ExportResult.CopyFailed)
    }

    @Test fun copyFails_permissionGone_is_permanent_SafFolderUnavailable() = runBlocking {
        val calls = Calls()
        val r = exporter(calls, copyToDoc = { _, _ -> throw java.io.IOException("revoked") },
            permissionHeld = { false }).export("s", emptyList())
        assertTrue(r is ExportResult.SafFolderUnavailable)
        assertTrue("setFailed" in calls.log)
    }

    @Test fun retryBudgetExhausted_escalates_to_permanent() = runBlocking {
        val r = exporter(copyToDoc = { _, _ -> throw java.io.IOException("busy") },
            permissionHeld = { true }, retryCount = 3).export("s", emptyList())
        assertTrue(r is ExportResult.SafFolderUnavailable)
    }

    @Test fun safValidateFails_deletes_partial_and_returns_transient() = runBlocking {
        var deleted = false
        val r = exporter(validateDoc = { false }, deleteDoc = { deleted = true; true }).export("s", emptyList())
        assertTrue(r is ExportResult.CopyFailed)
        assertTrue(deleted)
    }

    @Test fun createDoc_autoRename_capturesReturnedName() = runBlocking {
        var captured: String? = null
        exporter(createDoc = { "content://tree/doc/renamed" },
            nameProbe = { "Rova (1).mp4".also { captured = it } }).export("s", emptyList())
        assertEquals("Rova (1).mp4", captured)
    }

    @Test fun recover_validatedDoc_finalizes_without_recopy() = runBlocking {
        var copied = false
        val m = SessionManifest("s", 0, SessionConfig(1, 1, "x", 1, "Portrait"),
            emptyList(), ExportTier.SAF_DESTINATION,
            safTargetDocUri = "content://tree/doc/good", exportState = ExportState.COPYING)
        val r = exporter(validateDoc = { true }, copyToDoc = { _, _ -> copied = true })
            .recover(m, emptyList())
        assertTrue(r is RecoveryResult.Resumed)
        assertEquals(false, copied)
    }

    @Test fun recover_remuxes_from_segments_when_no_valid_doc() = runBlocking {
        var muxed = false
        val m = SessionManifest("s", 0, SessionConfig(1, 1, "x", 1, "Portrait"),
            emptyList(), ExportTier.SAF_DESTINATION,
            safTargetDocUri = null, exportState = ExportState.MUXING)
        val r = exporter(mux = { _, out -> muxed = true; out.writeBytes(ByteArray(8)) })
            .recover(m, listOf(File(tempDir, "seg.mp4")))
        assertTrue(r is RecoveryResult.Resumed)
        assertTrue(muxed)
    }
}

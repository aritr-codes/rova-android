package com.aritr.rova.data

import com.aritr.rova.service.dualrecord.VideoSide
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: SessionStore

    @After
    fun tearDown() {
        store.close()
    }

    private fun store(): SessionStore {
        store = SessionStore(tmp.newFolder("videos"))
        return store
    }

    private fun newSession(s: SessionStore): String =
        s.createSession(SessionConfig(10, 1, "1080p", 3, "Portrait")).sessionId

    @Test
    fun setVaultFinalized_setsStateAndPathClearsPublic() = runBlocking {
        val s = store(); val id = newSession(s)
        // Seed public pointers so we can assert they are cleared.
        s.setExportPending(id, "content://media/external/video/1")
        s.setExportSafTarget(id, "content://tree/doc/9")

        s.setVaultFinalized(id, vaultFilePath = "/vault/$id/Rova_x.mp4")

        val m = s.loadManifest(id)!!
        assertEquals(VaultState.VAULTED, m.vaultState)
        assertEquals("/vault/$id/Rova_x.mp4", m.vaultFilePath)
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertNull(m.pendingUri)
        assertNull(m.publicTargetPath)
        assertNull(m.safTargetDocUri)
    }

    @Test
    fun setVaultFinalizedForSide_portrait_setsSideVaultPathClearsSidePublic() = runBlocking {
        val s = store(); val id = newSession(s)
        s.setExportPendingForSide(id, VideoSide.PORTRAIT, "content://media/p")
        s.setExportFinalizedForSide(
            id,
            VideoSide.PORTRAIT,
            publicTargetPath = "/Movies/Rova/p.mp4",
            clearPrivateTempPath = false
        )

        s.setVaultFinalizedForSide(id, VideoSide.PORTRAIT, vaultFilePath = "/vault/$id/p.mp4")

        val m = s.loadManifest(id)!!
        assertEquals(VaultState.VAULTED, m.vaultState)
        assertEquals("/vault/$id/p.mp4", m.portraitVaultFilePath)
        assertNull(m.portraitPendingUri)
        assertNull(m.portraitPublicTargetPath)
    }

    @Test
    fun setVaultFinalizedForSide_landscape_setsSideVaultPathClearsSidePublic() = runBlocking {
        val s = store(); val id = newSession(s)
        s.setExportPendingForSide(id, VideoSide.LANDSCAPE, "content://media/l")
        s.setExportFinalizedForSide(
            id,
            VideoSide.LANDSCAPE,
            publicTargetPath = "/Movies/Rova/l.mp4",
            clearPrivateTempPath = false
        )

        s.setVaultFinalizedForSide(id, VideoSide.LANDSCAPE, vaultFilePath = "/vault/$id/l.mp4")

        val m = s.loadManifest(id)!!
        assertEquals(VaultState.VAULTED, m.vaultState)
        assertEquals("/vault/$id/l.mp4", m.landscapeVaultFilePath)
        assertNull(m.landscapePendingUri)
        assertNull(m.landscapePublicTargetPath)
    }

    @Test
    fun setVaultState_setsInFlightStateAndPreservesExistingPath() = runBlocking {
        val s = store(); val id = newSession(s)
        s.setVaultFinalized(id, vaultFilePath = "/vault/$id/Rova_x.mp4")

        // Begin a move-out: UNVAULTING, no path passed -> existing retained.
        s.setVaultState(id, VaultState.UNVAULTING)
        var m = s.loadManifest(id)!!
        assertEquals(VaultState.UNVAULTING, m.vaultState)
        assertEquals("/vault/$id/Rova_x.mp4", m.vaultFilePath)

        // Begin a move-in: VAULTING, path passed -> overwrites.
        s.setVaultState(id, VaultState.VAULTING, vaultFilePath = "/vault/$id/Rova_y.mp4")
        m = s.loadManifest(id)!!
        assertEquals(VaultState.VAULTING, m.vaultState)
        assertEquals("/vault/$id/Rova_y.mp4", m.vaultFilePath)
    }

    @Test
    fun setVaultMovedOut_backToPublicClearsVaultSetsPublished() = runBlocking {
        val s = store(); val id = newSession(s)
        s.setVaultFinalized(id, vaultFilePath = "/vault/$id/Rova_x.mp4")

        s.setVaultMovedOut(
            id,
            pendingUri = "content://media/external/video/42",
            publicTargetPath = "/Movies/Rova/Rova_x.mp4"
        )

        val m = s.loadManifest(id)!!
        assertEquals(VaultState.PUBLIC, m.vaultState)
        assertNull(m.vaultFilePath)
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertEquals("content://media/external/video/42", m.pendingUri)
        assertEquals("/Movies/Rova/Rova_x.mp4", m.publicTargetPath)
    }

    // B5 / ADR-0025 commit-before-finalize follow-up ------------------------

    @Test
    fun setPendingMoveOutTier1_commitsInFlightPendingUri() = runBlocking {
        val s = store(); val id = newSession(s)
        s.setVaultFinalized(id, vaultFilePath = "/vault/$id/Rova_x.mp4")
        s.setVaultState(id, VaultState.UNVAULTING)

        s.setPendingMoveOutTier1(id, "content://media/external/video/100")

        val m = s.loadManifest(id)!!
        assertEquals("content://media/external/video/100", m.pendingMoveOutUri)
        assertNull(m.pendingMoveOutPath)
    }

    @Test
    fun setPendingMoveOutPreQ_commitsInFlightPartPath() = runBlocking {
        val s = store(); val id = newSession(s)
        s.setVaultFinalized(id, vaultFilePath = "/vault/$id/Rova_x.mp4")
        s.setVaultState(id, VaultState.UNVAULTING)

        s.setPendingMoveOutPreQ(id, "/Movies/Rova/Rova_x.mp4.part")

        val m = s.loadManifest(id)!!
        assertEquals("/Movies/Rova/Rova_x.mp4.part", m.pendingMoveOutPath)
        assertNull(m.pendingMoveOutUri)
    }

    @Test
    fun setVaultMovedOut_clearsInFlightPendingMoveOutPointers() = runBlocking {
        val s = store(); val id = newSession(s)
        s.setVaultFinalized(id, vaultFilePath = "/vault/$id/Rova_x.mp4")
        s.setVaultState(id, VaultState.UNVAULTING)
        s.setPendingMoveOutTier1(id, "content://media/external/video/100")

        s.setVaultMovedOut(id, pendingUri = "content://media/external/video/42")

        val m = s.loadManifest(id)!!
        assertNull(m.pendingMoveOutUri)
        assertNull(m.pendingMoveOutPath)
        assertEquals("content://media/external/video/42", m.pendingUri)
    }

    // B5 #2 — move-IN must clear the now-deleted public pointers ------------

    @Test
    fun setVaultStateVaultedAndClearPublic_clearsPublicPointersPreservesVaultPath() = runBlocking {
        val s = store(); val id = newSession(s)
        s.setExportPending(id, "content://media/external/video/1")
        s.setExportSafTarget(id, "content://tree/doc/9")
        // Move-in records the vault path while still PUBLIC-pointed.
        s.setVaultState(id, VaultState.VAULTING, vaultFilePath = "/vault/$id/Rova_x.mp4")

        s.setVaultStateVaultedAndClearPublic(id)

        val m = s.loadManifest(id)!!
        assertEquals(VaultState.VAULTED, m.vaultState)
        assertEquals("/vault/$id/Rova_x.mp4", m.vaultFilePath)
        assertNull(m.pendingUri)
        assertNull(m.publicTargetPath)
        assertNull(m.safTargetDocUri)
    }
}

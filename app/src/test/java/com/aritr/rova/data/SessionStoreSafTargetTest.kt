package com.aritr.rova.data

import com.aritr.rova.service.dualrecord.VideoSide
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreSafTargetTest {

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
        s.createSession(SessionConfig(10, 1, "1080p", 3)).sessionId

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

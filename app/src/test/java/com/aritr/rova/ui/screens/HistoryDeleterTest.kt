package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM-only tests for the [HistoryDeleter] orchestration. Pins the
 * delete-then-discard order contract without an `AndroidViewModel`,
 * a real `ContentResolver`, or a real `SessionStore`.
 *
 * The order contract matters because:
 *   * Discarding the session manifest before the gallery delete
 *     succeeds would leak app-private segment files.
 *   * Discarding when the gallery delete fails leaves the user with
 *     a half-consistent state — visible artifact, no manifest.
 *   * The visible part of the operation (gallery delete) drives the
 *     success/failure return; manifest cleanup is best-effort and
 *     never flips the result.
 */
class HistoryDeleterTest {

    private fun item(sessionId: String? = null): VideoItem = VideoItem(
        file = File("/tmp/rova/${sessionId ?: "legacy"}.mp4"),
        thumbnail = null,
        resolution = "FHD",
        shareUri = null,
        sessionId = sessionId
    )

    @Test
    fun `manifest-backed item - artifact delete success triggers discardSession`() {
        val discardCalls = mutableListOf<String>()
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { sid -> discardCalls += sid },
        )
        val result = deleter.delete(item(sessionId = "s-1"))
        assertTrue(result)
        assertEquals(listOf("s-1"), discardCalls)
    }

    @Test
    fun `manifest-backed item - artifact delete failure does NOT trigger discardSession`() {
        // Critical contract: a half-completed delete (manifest gone,
        // gallery still present) is worse than a full failure. If the
        // gallery row could not be removed we must NOT remove the
        // manifest — the user might retry, and the manifest is the
        // only handle the recovery + history paths still have on the
        // session.
        val discardCalls = mutableListOf<String>()
        val deleter = HistoryDeleter(
            deleteArtifact = { false },
            discardSession = { sid -> discardCalls += sid },
        )
        val result = deleter.delete(item(sessionId = "s-2"))
        assertFalse(result)
        assertTrue("discardSession must not run on artifact failure", discardCalls.isEmpty())
    }

    @Test
    fun `legacy item - no sessionId means no discardSession`() {
        val discardCalls = mutableListOf<String>()
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { sid -> discardCalls += sid },
        )
        val result = deleter.delete(item(sessionId = null))
        assertTrue(result)
        assertTrue("legacy items must skip discardSession", discardCalls.isEmpty())
    }

    @Test
    fun `discardSession failure does NOT flip the artifact-delete result`() {
        // The artifact is gone from the user's gallery — the visible
        // outcome is success. A subsequent failure to clean the
        // app-private session dir is logged via onDiscardError but
        // must not cause the History screen to show "Could not
        // delete" for a recording the user can clearly see is gone.
        var loggedSid: String? = null
        var loggedError: Throwable? = null
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { error("disk gone") },
            onDiscardError = { sid, t ->
                loggedSid = sid
                loggedError = t
            }
        )
        val result = deleter.delete(item(sessionId = "s-3"))
        assertTrue(result)
        assertEquals("s-3", loggedSid)
        assertEquals("disk gone", loggedError?.message)
    }

    @Test
    fun `discardSession is not invoked when artifact delete fails - even with onDiscardError set`() {
        // Defense for the contract above. The error sink must stay
        // silent when the discard path is never reached.
        var loggedSid: String? = null
        val deleter = HistoryDeleter(
            deleteArtifact = { false },
            discardSession = { error("should not be called") },
            onDiscardError = { sid, _ -> loggedSid = sid }
        )
        deleter.delete(item(sessionId = "s-4"))
        assertNull(loggedSid)
    }
}

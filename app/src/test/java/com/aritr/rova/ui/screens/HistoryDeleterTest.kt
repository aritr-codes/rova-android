package com.aritr.rova.ui.screens

import com.aritr.rova.data.AudioMode
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM-only tests for the [HistoryDeleter] batch orchestration
 * (ADR-0036). Pins the two-step transaction — delete artifacts, then
 * commit (or retain) each session's manifest — without an
 * `AndroidViewModel`, a real `ContentResolver`, or a real
 * `SessionStore`.
 *
 * The order contract matters because the manifest is the only handle
 * the Library has on a session's artifacts: discarding it while any
 * artifact survives strands that artifact (Defect A, codex PR-B
 * last-pass 2026-07-03), and for MULTI_SEGMENT_KEPT sessions the
 * sibling files live inside the session directory, so a premature
 * discard destroys them (Defect B, 2026-07-06 branch analysis).
 */
class HistoryDeleterTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var store: SessionStore

    @Before
    fun setUpStore() {
        store = SessionStore(tmp.newFolder("videos"))
    }

    @After
    fun tearDownStore() {
        store.close()
    }

    private fun item(
        sessionId: String? = null,
        name: String = sessionId ?: "legacy",
    ): VideoItem = VideoItem(
        file = File("/tmp/rova/$name.mp4"),
        thumbnail = null,
        resolution = "FHD",
        shareUri = null,
        sessionId = sessionId,
    )

    // ---- Carried-over single-item contract (pre-ADR-0036 suite) ----

    @Test
    fun `single item covering its session - artifact success triggers one discard`() {
        val discardCalls = mutableListOf<String>()
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { sid -> discardCalls += sid },
        )
        val target = item(sessionId = "s-1")
        val failed = deleter.deleteAll(listOf(target), listedItems = listOf(target))
        assertTrue(failed.isEmpty())
        assertEquals(listOf("s-1"), discardCalls)
    }

    @Test
    fun `artifact delete failure does NOT trigger discardSession`() {
        val discardCalls = mutableListOf<String>()
        val deleter = HistoryDeleter(
            deleteArtifact = { false },
            discardSession = { sid -> discardCalls += sid },
        )
        val target = item(sessionId = "s-2")
        val failed = deleter.deleteAll(listOf(target), listedItems = listOf(target))
        assertEquals(setOf(target.stableKey), failed)
        assertTrue("discardSession must not run on artifact failure", discardCalls.isEmpty())
    }

    @Test
    fun `legacy item - no sessionId means no discardSession`() {
        val discardCalls = mutableListOf<String>()
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { sid -> discardCalls += sid },
        )
        val target = item(sessionId = null)
        val failed = deleter.deleteAll(listOf(target), listedItems = listOf(target))
        assertTrue(failed.isEmpty())
        assertTrue("legacy items must skip discardSession", discardCalls.isEmpty())
    }

    @Test
    fun `discardSession failure is swallowed and does NOT mark the batch failed`() {
        // The artifacts are gone from the user's gallery — the visible
        // outcome is success. A ghost manifest is the acceptable
        // residue (ADR-0036 I3); reporting it as a delete failure would
        // claim a visible file survived when none did.
        var loggedSid: String? = null
        var loggedError: Throwable? = null
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { error("disk gone") },
            onDiscardError = { sid, t ->
                loggedSid = sid
                loggedError = t
            },
        )
        val target = item(sessionId = "s-3")
        val failed = deleter.deleteAll(listOf(target), listedItems = listOf(target))
        assertTrue(failed.isEmpty())
        assertEquals("s-3", loggedSid)
        assertEquals("disk gone", loggedError?.message)
    }

    @Test
    fun `error sink stays silent when the discard step is never reached`() {
        var loggedSid: String? = null
        val deleter = HistoryDeleter(
            deleteArtifact = { false },
            discardSession = { error("should not be called") },
            onDiscardError = { sid, _ -> loggedSid = sid },
        )
        val target = item(sessionId = "s-4")
        deleter.deleteAll(listOf(target), listedItems = listOf(target))
        assertNull(loggedSid)
    }

    // ---- ADR-0036 batch transaction ----

    @Test
    fun `defect A regression - dualshot partial failure retains the manifest`() {
        // Portrait artifact deletes OK, landscape fails. Pre-ADR-0036
        // the portrait success discarded the shared manifest and the
        // surviving landscape file became an unreachable orphan in the
        // system gallery (codex PR-B last-pass, 2026-07-03).
        val discardCalls = mutableListOf<String>()
        val portrait = item(sessionId = "s-dual", name = "s-dual-p")
        val landscape = item(sessionId = "s-dual", name = "s-dual-l")
        val deleter = HistoryDeleter(
            deleteArtifact = { it.stableKey == portrait.stableKey },
            discardSession = { sid -> discardCalls += sid },
        )
        val failed = deleter.deleteAll(
            listOf(portrait, landscape),
            listedItems = listOf(portrait, landscape),
        )
        assertEquals(setOf(landscape.stableKey), failed)
        assertTrue("manifest must be retained (I1)", discardCalls.isEmpty())
    }

    @Test
    fun `dualshot full success - discard fires exactly once per session`() {
        val discardCalls = mutableListOf<String>()
        val portrait = item(sessionId = "s-dual", name = "s-dual-p")
        val landscape = item(sessionId = "s-dual", name = "s-dual-l")
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { sid -> discardCalls += sid },
        )
        val failed = deleter.deleteAll(
            listOf(portrait, landscape),
            listedItems = listOf(portrait, landscape),
        )
        assertTrue(failed.isEmpty())
        assertEquals(listOf("s-dual"), discardCalls)
    }

    @Test
    fun `defect B regression - segment subset never discards the session`() {
        // 1 of 3 kept segments in the batch. Pre-ADR-0036 the discard
        // ran deleteRecursively on the session dir and destroyed the
        // sibling segment files (2026-07-06 branch analysis).
        val discardCalls = mutableListOf<String>()
        val seg0 = item(sessionId = "s-multi", name = "s-multi-seg0")
        val seg1 = item(sessionId = "s-multi", name = "s-multi-seg1")
        val seg2 = item(sessionId = "s-multi", name = "s-multi-seg2")
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { sid -> discardCalls += sid },
        )
        val failed = deleter.deleteAll(
            listOf(seg0),
            listedItems = listOf(seg0, seg1, seg2),
        )
        assertTrue(failed.isEmpty())
        assertTrue("sibling segments survive → no discard (I2)", discardCalls.isEmpty())
    }

    @Test
    fun `discard executes only after ALL artifact deletes have run`() {
        // Pins the transaction shape itself: step 1 (artifacts) fully
        // precedes step 2 (commit). Pre-ADR-0036 the discard fired
        // inside the per-item loop.
        val events = mutableListOf<String>()
        val portrait = item(sessionId = "s-dual", name = "s-dual-p")
        val landscape = item(sessionId = "s-dual", name = "s-dual-l")
        val deleter = HistoryDeleter(
            deleteArtifact = { events += "artifact:${it.stableKey}"; true },
            discardSession = { sid -> events += "discard:$sid" },
        )
        deleter.deleteAll(
            listOf(portrait, landscape),
            listedItems = listOf(portrait, landscape),
        )
        assertEquals(
            listOf(
                "artifact:${portrait.stableKey}",
                "artifact:${landscape.stableKey}",
                "discard:s-dual",
            ),
            events,
        )
    }

    @Test
    fun `one artifact failure does not abort the rest of the batch`() {
        // Best-effort artifact pass (retention relies on this): a
        // failure is recorded and the remaining items are still
        // attempted.
        val attempted = mutableListOf<String>()
        val a = item(sessionId = "sA")
        val b = item(sessionId = "sB")
        val c = item(sessionId = "sC")
        val deleter = HistoryDeleter(
            deleteArtifact = { attempted += it.stableKey; it.sessionId != "sB" },
            discardSession = { },
        )
        val failed = deleter.deleteAll(
            listOf(a, b, c),
            listedItems = listOf(a, b, c),
        )
        assertEquals(listOf(a.stableKey, b.stableKey, c.stableKey), attempted)
        assertEquals(setOf(b.stableKey), failed)
    }

    @Test
    fun `retention boundary - one side in batch while sibling stays listed keeps the manifest`() {
        // The retention keep-window slices per item, so the surplus can
        // contain one DualShot side while its sibling is KEPT. The kept
        // side's visibility must survive.
        val discardCalls = mutableListOf<String>()
        val surplusSide = item(sessionId = "s-dual", name = "s-dual-l")
        val keptSide = item(sessionId = "s-dual", name = "s-dual-p")
        val deleter = HistoryDeleter(
            deleteArtifact = { true },
            discardSession = { sid -> discardCalls += sid },
        )
        val failed = deleter.deleteAll(
            listOf(surplusSide),
            listedItems = listOf(surplusSide, keptSide),
        )
        assertTrue(failed.isEmpty())
        assertTrue("kept sibling still listed → no discard", discardCalls.isEmpty())
    }

    // ---- ADR-0037 §1: segments-array stability ----

    @Test
    fun `subset delete never reorders or compacts the segments array`() {
        // Real SessionStore (not the fake lambdas above): a 3-segment
        // MULTI_SEGMENT_KEPT session, subset-delete ONE segment's file
        // (siblings stay listed → I2 keeps the manifest per the defect-B
        // test above), then reload and diff manifest.segments byte-for-byte
        // against the pre-delete array (ADR-0037 §1 — append-only at tail,
        // never reordered/compacted by a deletion).
        val sid = "s-adr37-stability"
        val segments = (0..2).map { i ->
            SegmentRecord(
                filename = "segment_%04d.mp4".format(i + 1),
                durationMs = 1_000L,
                sizeBytes = 1L,
                sha1 = "sha-$i",
            )
        }
        val dir = store.sessionDir(sid).also { it.mkdirs() }
        segments.forEach { seg -> File(dir, seg.filename).writeBytes(byteArrayOf(0x00)) }
        val manifest = SessionManifest(
            sessionId = sid,
            startedAt = 0L,
            config = SessionConfig(durationSeconds = 5, intervalSeconds = 60, resolution = "720p", loopCount = 0),
            segments = segments,
            exportTier = ExportTier.TIER1_API29_PLUS,
            exportState = ExportState.NOT_STARTED,
            terminated = Terminated.MULTI_SEGMENT_KEPT,
            terminatedAt = 1L,
            stopRequested = false,
            stopReason = StopReason.NONE,
            audioMode = AudioMode.VIDEO_ONLY,
        )
        File(dir, "manifest.json").writeText(manifest.toJson().toString())

        val items = segments.mapIndexed { index, seg ->
            item(sessionId = sid, name = "unused-$index").copy(
                file = File(dir, seg.filename),
                segmentIndex = index,
            )
        }
        val deleter = HistoryDeleter(
            deleteArtifact = { it.file?.delete() ?: false },
            discardSession = { s -> store.discardSession(s) },
        )
        // Delete only segment 0's file; segments 1 and 2 stay listed —
        // sibling survivors keep the manifest (I2), so discardSession
        // must never fire and the array must survive untouched.
        val failed = deleter.deleteAll(listOf(items[0]), listedItems = items)
        assertTrue(failed.isEmpty())

        val reloaded = store.loadManifest(sid)!!
        assertEquals(
            "manifest.segments must stay element-for-element identical after a subset delete",
            segments,
            reloaded.segments,
        )
    }
}

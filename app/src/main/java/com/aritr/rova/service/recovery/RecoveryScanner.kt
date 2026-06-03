package com.aritr.rova.service.recovery

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionStore
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.ServiceController
import com.aritr.rova.utils.MediaFileInspection
import com.aritr.rova.utils.RovaLog
import com.aritr.rova.utils.inspectMediaFile
import java.io.File

/**
 * Phase 1.5 recovery classifier (ADR 0005).
 *
 * Single entry point: [classify]. Dependency-injected for unit testing —
 * tests substitute a fake clock, fake media inspector, fake live-session
 * registry, and fake SHA-1 source so the matrix can be exercised without
 * Android, MediaExtractor, or filesystem fixtures larger than empty
 * placeholder files.
 *
 * Invariants (mirror ADR 0005 §"Concurrency Invariants"):
 * - Append happens BEFORE [SessionStore.markTerminated] for `T == null` rows
 *   so the terminal record reflects the final segment set in a single
 *   atomic snapshot. Both go through [SessionStore]'s serial
 *   `persistDispatcher`, so submission order == observable order.
 * - Append is gated on `T != COMPLETED`. Stray segment files in a COMPLETED
 *   session surface as anomalies for human review only.
 * - The classifier writes only [Terminated.USER_STOPPED] or
 *   [Terminated.KILLED_FORCE_STOP]. It NEVER writes [Terminated.KILLED_BY_SYSTEM]
 *   ([com.aritr.rova.service.RovaTickReceiver]-owned) or [Terminated.COMPLETED]
 *   ([com.aritr.rova.service.RovaRecordingService.performMerge]-owned).
 *
 * Phase 1.5 emits [DiscardEligibility] flags but **never deletes**. Physical
 * deletion is owned by Phase 1.7 cleanup (post-export-recovery, ADR 0003) or
 * by explicit user action through Phase 2 UI.
 */
class RecoveryScanner(
    private val sessionStore: SessionStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val inspect: (File) -> MediaFileInspection = ::inspectMediaFile,
    private val liveSessionId: () -> String? = { ServiceController.current()?.sessionId },
    private val sha1: (File) -> String = SessionStore::sha1Of
) {

    /**
     * Classify every session directory under [SessionStore.rootDir]. Returns
     * one [SessionClassification] per session id. Side effects per session:
     * append validated contiguous orphan prefix (when `T != COMPLETED`),
     * then write terminal record (when `T == null`).
     */
    suspend fun classifyAll(scanStartMillis: Long): Map<String, SessionClassification> {
        val ids = sessionStore.listSessionIds()
        val out = LinkedHashMap<String, SessionClassification>(ids.size)
        for (sid in ids) {
            out[sid] = classify(sid, scanStartMillis)
        }
        return out
    }

    /**
     * Classify a single session. Implements ADR 0005's decision matrix and
     * orphan-ordering rules. See class KDoc for invariants.
     */
    suspend fun classify(sessionId: String, scanStartMillis: Long): SessionClassification {
        // Invariant 5 — per-session live re-check. The live service owns
        // its session; the scan never touches it.
        if (liveSessionId() == sessionId) {
            RovaLog.d("RecoveryScanner: $sessionId is live-owned; skipping")
            return skipped(sessionId)
        }

        val manifest = sessionStore.loadManifest(sessionId)
        if (manifest == null) {
            // Manifest missing or corrupt. Cannot classify — caller retries
            // next scan (the dir itself is not deleted; user-driven discard
            // can clean it up).
            RovaLog.w("RecoveryScanner: $sessionId manifest unreadable; BLOCKED")
            return skipped(sessionId)
        }

        // Invariant 6 — age filter. A session whose createSession just
        // released the mutex but whose receiver work is still in flight
        // could be reached here despite the drain; the 5 s window covers it.
        if (manifest.startedAt > scanStartMillis - AGE_FILTER_MILLIS) {
            RovaLog.d("RecoveryScanner: $sessionId age-filtered (startedAt=${manifest.startedAt}, scanStart=$scanStartMillis); BLOCKED")
            return skipped(sessionId)
        }

        // ADR 0006 §"Cross-Phase Ordering Invariant" (B14 + B17 + B22):
        // sessions with `exportState in {MUXING, COPYING, FINALIZED} &&
        // terminated == null` are owned by Phase 1.7 export-recovery.
        // Phase 1.5 must NOT write a terminal value — first-writer-wins
        // would lock the wrong terminal forever. `FAILED` and
        // `NOT_STARTED` are Phase 1.5 inputs and fall through to the
        // normal classification matrix.
        if (manifest.terminated == null &&
            manifest.exportState in EXPORT_IN_FLIGHT_OR_FINALIZED) {
            RovaLog.d(
                "RecoveryScanner: $sessionId exportState=${manifest.exportState}" +
                    " && T==null; SKIPPED_EXPORT_PENDING (Phase 1.7 owns)"
            )
            return SessionClassification(
                sessionId = sessionId,
                terminalAction = TerminalAction.SKIPPED_EXPORT_PENDING,
                eligibility = DiscardEligibility.BLOCKED,
                anomalies = emptyList(),
                appendedSegmentFilenames = emptyList()
            )
        }

        val dir = sessionStore.sessionDir(sessionId)
        val files = dir.listFiles()?.toList().orEmpty()

        // Partition by name shape.
        val segmentRegex = SEGMENT_REGEX
        val diskSegments: List<DiskSegment> = files.mapNotNull { f ->
            val match = segmentRegex.matchEntire(f.name) ?: return@mapNotNull null
            DiskSegment(
                file = f,
                index = match.groupValues[1].toInt(),
                side = parseSide(match.groupValues[2])
            )
        }
        val unknownFilenames: List<String> = files
            .filter { f ->
                f.name != SessionStore.MANIFEST_NAME &&
                    f.name != SessionStore.MANIFEST_NAME_TMP &&
                    segmentRegex.matchEntire(f.name) == null
            }
            .map { it.name }

        // Partition manifest records by canonical-filename match. The
        // scanner refuses to silently drop non-canonical records (would
        // violate ADR 0005's no-silent-reconciliation rule); they surface
        // as MalformedManifestRecord anomalies.
        //
        // Manifest-internal duplicates (two records with the same parsed
        // index) are also detected here. With canonical records, the
        // schema permits this even though it's nonsensical; the scan
        // reports rather than silently keeping the last-wins entry.
        // Key = (parsedIndex, record.side). A P+L pair at the same ordinal
        // has different sides so it maps to TWO distinct keys — not a dup.
        val canonicalRecords = mutableListOf<Pair<Pair<Int, com.aritr.rova.service.dualrecord.VideoSide?>, SegmentRecord>>()
        val malformedManifestFilenames = mutableListOf<String>()
        val manifestKeyCounts = mutableMapOf<Pair<Int, com.aritr.rova.service.dualrecord.VideoSide?>, Int>()
        for (rec in manifest.segments) {
            val match = segmentRegex.matchEntire(rec.filename)
            if (match == null) {
                malformedManifestFilenames += rec.filename
                continue
            }
            val idx = match.groupValues[1].toInt()
            val key = idx to rec.side
            canonicalRecords += key to rec
            manifestKeyCounts[key] = (manifestKeyCounts[key] ?: 0) + 1
        }
        val manifestInternalDuplicateKeys: List<Pair<Int, com.aritr.rova.service.dualrecord.VideoSide?>> =
            manifestKeyCounts.filter { it.value > 1 }.keys.toList()
        // Flatten to distinct sorted indices for Anomaly.DuplicateSegment.
        val manifestInternalDuplicateIndices: List<Int> = manifestInternalDuplicateKeys
            .map { it.first }.distinct().sorted()
        // For lookups, last-write-wins is fine — duplicates are reported
        // separately, and validation/missing decisions for the duplicate
        // key produce the same answer regardless of which record is kept.
        val manifestByKey: Map<Pair<Int, com.aritr.rova.service.dualrecord.VideoSide?>, SegmentRecord> =
            canonicalRecords.toMap()
        val idxMaxManifest: Int = manifestByKey.keys.maxOfOrNull { it.first } ?: 0

        // Inspect every segment file we'll touch. One inspect call per file —
        // results cached in this map for both validity and duration.
        val inspections: Map<File, MediaFileInspection> = diskSegments
            .associate { it.file to inspect(it.file) }

        // Manifest-segment evaluation keyed by (index, side).
        // A P+L pair occupies two distinct keys; each is independently
        // evaluated for missing/invalid status.
        val missingKeys = mutableListOf<Pair<Int, com.aritr.rova.service.dualrecord.VideoSide?>>()
        val invalidManifestKeys = mutableListOf<Pair<Int, com.aritr.rova.service.dualrecord.VideoSide?>>()
        for ((key, rec) in manifestByKey) {
            val (idx, side) = key
            val onDiskAtKey = diskSegments.filter { it.index == idx && it.side == side }
            if (onDiskAtKey.isEmpty()) {
                missingKeys += key
                continue
            }
            // Choose the file matching the manifest filename if present;
            // otherwise the first disk match (duplicate detection runs
            // separately).
            val canonical = onDiskAtKey.firstOrNull { it.file.name == rec.filename }
                ?: onDiskAtKey.first()
            val ok = inspections[canonical.file]?.isValid == true
            if (!ok) invalidManifestKeys += key
        }
        // Flatten keys to indices for anomaly reporting.
        val missingIndices: List<Int> = missingKeys.map { it.first }.distinct().sorted()
        val invalidManifestIndices: List<Int> = invalidManifestKeys.map { it.first }.distinct().sorted()

        // Duplicate detection — three sources:
        // 1. Two or more disk files at the same (index, side) key. A P+L pair
        //    at (1, PORTRAIT) + (1, LANDSCAPE) occupies two distinct keys and
        //    is NOT a duplicate. Two segment_0001.mp4 files at (1, null) ARE.
        // 2. Disk file at a (index, side) key where the manifest record's
        //    filename is different — only reachable for non-canonical manifest
        //    records, caught upstream as MalformedManifestRecord.
        // 3. Two manifest records with the same (index, side) key
        //    (manifestInternalDuplicateIndices, computed during partition).
        val diskDuplicateKeys: List<Pair<Int, com.aritr.rova.service.dualrecord.VideoSide?>> =
            diskSegments
                .groupBy { it.index to it.side }
                .filter { (key, group) ->
                    if (group.size > 1) return@filter true
                    val rec = manifestByKey[key] ?: return@filter false
                    group.first().file.name != rec.filename
                }
                .keys
                .toList()
        val diskDuplicateIndices: List<Int> = diskDuplicateKeys.map { it.first }.distinct().sorted()
        val duplicateIndices: List<Int> =
            (diskDuplicateIndices + manifestInternalDuplicateIndices).distinct().sorted()

        // Candidate orphans: disk segments whose filename does not appear
        // in manifest.segments. Sorted by parsed index for prefix logic.
        val manifestFilenames: Set<String> = manifestByKey.values.map { it.filename }.toSet()
        val candidateOrphans: List<DiskSegment> = diskSegments
            .filter { it.file.name !in manifestFilenames }
            .sortedBy { it.index }
        val invalidOrphanFilenames: List<String> = candidateOrphans
            .filter { inspections[it.file]?.isValid != true }
            .map { it.file.name }

        // Longest contiguous valid prefix starting at idxMaxManifest + 1.
        // For P+L sessions (mode == "PortraitLandscape"): OQ-6 default —
        // require BOTH sides present and valid at each ordinal before
        // appending. A missing or invalid side breaks the prefix walk;
        // the half-clip surfaces as an orphan/invalid anomaly.
        // For single-mode sessions: unchanged — walk one file per ordinal.
        val orphansAboveManifest: List<DiskSegment> = candidateOrphans
            .filter { it.index > idxMaxManifest }
            .sortedBy { it.index }
        val isPortraitLandscape = manifest.config.mode == "PortraitLandscape"
        val appendablePrefix: List<DiskSegment> = run {
            val prefix = mutableListOf<DiskSegment>()
            var expected = idxMaxManifest + 1
            if (isPortraitLandscape) {
                // Group orphans above manifest by index for pair lookup.
                val orphansByIndex: Map<Int, List<DiskSegment>> =
                    orphansAboveManifest.groupBy { it.index }
                while (true) {
                    val group = orphansByIndex[expected] ?: break
                    val portrait = group.firstOrNull {
                        it.side == com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT
                    }
                    val landscape = group.firstOrNull {
                        it.side == com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE
                    }
                    // Require both sides present and valid.
                    if (portrait == null || landscape == null) break
                    if (inspections[portrait.file]?.isValid != true) break
                    if (inspections[landscape.file]?.isValid != true) break
                    prefix += portrait
                    prefix += landscape
                    expected += 1
                }
            } else {
                for (ds in orphansAboveManifest) {
                    if (ds.index != expected) break
                    if (inspections[ds.file]?.isValid != true) break
                    prefix += ds
                    expected += 1
                }
            }
            prefix
        }
        // OrphanSegmentAnomaly captures EVERY valid candidate orphan that
        // the scanner did not append (ADR 0005 §"Anomaly Catalog" — Phase 2
        // UI must enumerate every surviving artifact). That includes:
        // - For T == COMPLETED: every valid candidate (no append runs at all).
        // - For T != COMPLETED: post-gap orphans above max, AND below-or-
        //   equal-max orphans (whose index is already claimed by a manifest
        //   record under a different filename, or sits inside a numbering
        //   gap the manifest didn't fill). The earlier "post-gap only" form
        //   silently lost the latter case.
        val T = manifest.terminated
        val canAppend = T != Terminated.COMPLETED
        val appendableFiles: Set<File> = if (canAppend) {
            appendablePrefix.map { it.file }.toSet()
        } else {
            emptySet()
        }
        val orphanAnomalyIndices: List<Int> = candidateOrphans
            .filter { it.file !in appendableFiles }
            .filter { inspections[it.file]?.isValid == true }
            .map { it.index }
            .sorted()

        // Assemble anomalies in stable order.
        val anomalies = mutableListOf<Anomaly>()
        if (missingIndices.isNotEmpty()) {
            anomalies += Anomaly.MissingSegment(missingIndices.sorted())
        }
        if (invalidManifestIndices.isNotEmpty()) {
            anomalies += Anomaly.InvalidManifestSegment(invalidManifestIndices.sorted())
        }
        if (orphanAnomalyIndices.isNotEmpty()) {
            anomalies += Anomaly.OrphanSegment(orphanAnomalyIndices)
        }
        if (invalidOrphanFilenames.isNotEmpty()) {
            anomalies += Anomaly.InvalidOrphan(invalidOrphanFilenames.sorted())
        }
        if (duplicateIndices.isNotEmpty()) {
            anomalies += Anomaly.DuplicateSegment(duplicateIndices)
        }
        if (unknownFilenames.isNotEmpty()) {
            anomalies += Anomaly.UnknownArtifact(unknownFilenames.sorted())
        }
        if (malformedManifestFilenames.isNotEmpty()) {
            anomalies += Anomaly.MalformedManifestRecord(malformedManifestFilenames.sorted())
        }

        // Mutations — strict order:
        // 1. Append (gated on T != COMPLETED, see canAppend above) BEFORE
        //    markTerminated, so the terminal record reflects the appended
        //    segment set.
        // 2. markTerminated (gated on T == null).
        val appendedFilenames = mutableListOf<String>()
        if (canAppend && appendablePrefix.isNotEmpty()) {
            for (ds in appendablePrefix) {
                val inspection = inspections[ds.file] ?: MediaFileInspection.INVALID
                val durationMs = inspection.durationMs
                val record = SegmentRecord(
                    filename = ds.file.name,
                    durationMs = durationMs,
                    sizeBytes = ds.file.length(),
                    sha1 = computeSha1(ds.file),
                    side = ds.side
                )
                sessionStore.appendSegment(sessionId, record)
                appendedFilenames += ds.file.name
                RovaLog.d("RecoveryScanner: appended orphan ${ds.file.name} to $sessionId")
            }
        }

        // ADR 0006 §"Migration table":
        // - stopRequested=true branch carries the user's prior intent →
        //   StopReason.USER.
        // - no-surviving-signal branch is a system kill (no human intent
        //   reached the manifest) → StopReason.NONE.
        val terminalAction: TerminalAction = when {
            T != null -> TerminalAction.ALREADY_TERMINAL
            manifest.stopRequested -> {
                sessionStore.markTerminated(sessionId, Terminated.USER_STOPPED, StopReason.USER)
                TerminalAction.WROTE_USER_STOPPED
            }
            else -> {
                sessionStore.markTerminated(sessionId, Terminated.KILLED_FORCE_STOP, StopReason.NONE)
                TerminalAction.WROTE_KILLED_FORCE_STOP
            }
        }

        // Eligibility from final state.
        val survivingManifestSegmentCount =
            manifestByKey.size - missingKeys.size - invalidManifestKeys.size + appendedFilenames.size
        val survivingOrphanCount = candidateOrphans.size - appendedFilenames.size
        val anySurvivors = survivingManifestSegmentCount > 0 ||
            survivingOrphanCount > 0 ||
            unknownFilenames.isNotEmpty()
        val eligibility = when {
            anomalies.isNotEmpty() || anySurvivors -> DiscardEligibility.OFFER_DISCARD
            // ADR 0005 §"Discard Eligibility" — a COMPLETED session is a
            // finished recording the user owns (the manifest-backed Library
            // index); it is NEVER auto-deleted by the cleanup pass, only by an
            // explicit user delete or the opt-in retention cleaner. Covers both
            // COMPLETED writers (live performMerge + the late-terminal
            // ExportRecoveryRunner write, which has no segment-count guard, so a
            // degenerate empty-segments COMPLETED would otherwise fall through to
            // AUTO_DISCARD_ELIGIBLE and be deleted). USER_STOPPED / KILLED_* empty
            // shells stay auto-eligible. (codex review, completed-session
            // retention contract.)
            T == Terminated.COMPLETED -> DiscardEligibility.OFFER_DISCARD
            else -> DiscardEligibility.AUTO_DISCARD_ELIGIBLE
        }

        return SessionClassification(
            sessionId = sessionId,
            terminalAction = terminalAction,
            eligibility = eligibility,
            anomalies = anomalies,
            appendedSegmentFilenames = appendedFilenames
        )
    }

    private fun computeSha1(file: File): String =
        try {
            sha1(file)
        } catch (t: Throwable) {
            RovaLog.w("RecoveryScanner: sha1 failed for ${file.name}", t)
            ""
        }

    private fun skipped(sessionId: String): SessionClassification = SessionClassification(
        sessionId = sessionId,
        terminalAction = TerminalAction.SKIPPED,
        eligibility = DiscardEligibility.BLOCKED,
        anomalies = emptyList(),
        appendedSegmentFilenames = emptyList()
    )

    private data class DiskSegment(
        val file: File,
        val index: Int,
        val side: com.aritr.rova.service.dualrecord.VideoSide?
    )

    private fun parseSide(group2: String): com.aritr.rova.service.dualrecord.VideoSide? = when (group2) {
        "P" -> com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT
        "L" -> com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE
        else -> null
    }

    companion object {
        /**
         * ADR 0005 §"State / Evidence Vocabulary": canonical segment filename
         * shape. Matches [SessionStore.nextSegmentFilename]. Phase 1.5
         * acceptance includes a static check that no `seg_` variant slips in.
         */
        val SEGMENT_REGEX = Regex("""^segment_(\d{4})(?:_([PL]))?\.mp4$""")

        /**
         * ADR 0005 §"Concurrency Invariants" item 6 — age filter window.
         * Sessions younger than this (relative to scan start) are skipped to
         * avoid a race with newly-started recordings whose receiver work
         * raced past the drain.
         */
        const val AGE_FILTER_MILLIS = 5_000L

        /**
         * ADR 0006 §"Cross-Phase Ordering Invariant" (B17): the explicit
         * set of [ExportState] values where Phase 1.5 yields ownership to
         * Phase 1.7. NOT a `!= NOT_STARTED` denylist — that would
         * incorrectly skip [ExportState.FAILED], stranding it forever.
         */
        val EXPORT_IN_FLIGHT_OR_FINALIZED: Set<ExportState> = setOf(
            ExportState.MUXING,
            ExportState.COPYING,
            ExportState.FINALIZED
        )
    }
}

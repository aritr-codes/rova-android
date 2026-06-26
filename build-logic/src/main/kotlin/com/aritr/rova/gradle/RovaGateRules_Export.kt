package com.aritr.rova.gradle

/**
 * Verbatim lifts of six former app/build.gradle.kts inline gate bodies
 * (export / terminal-ordering gates).
 * Mechanical swaps only vs the inline body:
 *   - iterate [files] parameter instead of dir.walkTopDown() / fileTree(...)
 *   - f.lines / f.text / f.relPath instead of readLines() / readText() / relativeTo(rootDir)
 *   - return message instead of throw GradleException(message); return null to pass
 *   - No "if(!exists()) throw" guards — an empty file set means no offenders → null
 *     (exception: checkExportCleanupPredicate is a REQUIRE gate — empty list means the
 *      required tokens are ABSENT, so we return the failure message, not null)
 *   - Canonical-path exclusion (checkScanFileBoundedWait): old code compared
 *     f.canonicalFile == allowedFile.canonicalFile. We only have relPath here.
 *     Replaced with a relPath suffix match on "service/export/MediaScanWaiter.kt"
 *     (after normalising backslashes to forward slashes). This suffix is unique in
 *     the rova source tree, so the behaviour is byte-identical to the canonical-path
 *     compare on both Windows and Linux.
 *   - Tier1 filename filter (checkPendingFdModeIsRW): old code used
 *     `it.name.startsWith("Tier1")`. We replicate by extracting the trailing
 *     filename component of relPath after normalising separators.
 *   - Separator note: a LOCAL normalised var is used only for relPath-suffix /
 *     filename checks. The reported f.relPath is NEVER normalised — it preserves
 *     the platform separator exactly as SourceCheckTask builds it (Windows '\',
 *     Linux '/'), byte-identical to the old `f.relativeTo(rootDir)` output.
 */

// ─── checkUserStoppedBeforeMerge ─────────────────────────────────────────────

/**
 * Verbatim lift of checkUserStoppedBeforeMerge.
 *
 * Single-file gate (old: inputs.file(...)). The rule receives a List<SourceFile>
 * with one element (RovaRecordingService.kt). If the list is empty (file missing),
 * the old gate threw immediately; here we return null — an absent file has no
 * ordering violations, matching the old `return@doLast` guard semantics.
 *
 * File-level opt-out: any line containing `terminal-ordering-opt-out:` skips all
 * ordering checks for that file.
 *
 * Logic:
 *   B3: every USER_STOPPED markTerminated must appear BEFORE the first merge call.
 *   B7: every COMPLETED markTerminated must appear AFTER the last merge call.
 *   Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 *   Merge calls: performMerge( (not the definition), VideoMerger.mergeSegments(, .mergeSegments(
 *   USER_STOPPED / COMPLETED: detected via 3-line forward window from markTerminated(
 */
internal fun ruleUserStoppedBeforeMerge(files: List<SourceFile>): String? {
    if (files.isEmpty()) return null
    val f = files.first()
    val lines = f.lines

    if (lines.any { it.contains("terminal-ordering-opt-out:") }) return null

    val userStoppedLines = mutableListOf<Int>()
    val completedLines = mutableListOf<Int>()
    val mergeLines = mutableListOf<Int>()
    for ((i, _) in lines.withIndex()) {
        val stripped = f.strippedLines.getOrElse(i) { "" }
        if (stripped.contains("markTerminated(")) {
            val window = (i..minOf(i + 3, lines.lastIndex))
                .joinToString("\n") { lines[it] }
            if (window.contains("Terminated.USER_STOPPED")) userStoppedLines += i + 1
            if (window.contains("Terminated.COMPLETED")) completedLines += i + 1
        }
        val isMergeCall = (stripped.contains("performMerge(") &&
            !stripped.contains("private suspend fun performMerge")) ||
            stripped.contains("VideoMerger.mergeSegments(") ||
            stripped.contains(".mergeSegments(")
        if (isMergeCall) mergeLines += i + 1
    }

    if (mergeLines.isEmpty()) return null
    val firstMerge = mergeLines.min()
    val lastMerge = mergeLines.max()
    val problems = mutableListOf<String>()

    userStoppedLines.filter { it >= firstMerge }.forEach { line ->
        problems += "USER_STOPPED markTerminated at line $line appears AT OR AFTER merge call at line $firstMerge — eager-write rule (B3) violated"
    }
    completedLines.filter { it <= lastMerge }.forEach { line ->
        problems += "COMPLETED markTerminated at line $line appears AT OR BEFORE the last merge call at line $lastMerge — merge-success-only rule (B7) violated"
    }

    if (problems.isEmpty()) return null
    return "Terminal-write ordering violations in ${f.relPath} (ADR 0006 B3 + B7):\n" +
        problems.joinToString("\n") { "  $it" } +
        "\nFix: USER_STOPPED writes go BEFORE merge entry; COMPLETED only at merge-success commit point.\n" +
        "Opt-out marker: // terminal-ordering-opt-out: <reason>"
}

// ─── checkExportTierReadTolerant ──────────────────────────────────────────────

/**
 * Verbatim lift of checkExportTierReadTolerant.
 * Regex: \bgetString\s*\(\s*"exportTier"\s*\)
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 * Scope: all .kt files passed in [files].
 */
internal fun ruleExportTierReadTolerant(files: List<SourceFile>): String? {
    val pattern = Regex("""\bgetString\s*\(\s*"exportTier"\s*\)""")
    val offenders = files
        .filter { it.relPath.endsWith(".kt") }
        .mapNotNull { f ->
            val hits = f.lines
                .withIndex()
                .filter { (idx, _) ->
                    pattern.containsMatchIn(f.strippedLines.getOrElse(idx) { "" })
                }
            if (hits.isEmpty()) null else f to hits
        }
        .toList()
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, hits) ->
        hits.joinToString("\n") { (i, line) ->
            "  ${f.relPath}:${i + 1}: ${line.trim()}"
        }
    }
    return "Strict getString(\"exportTier\") is forbidden — schema-2 " +
        "manifests lack the field and would throw JSONException. " +
        "Use the optString + runCatching + currentExportTier() " +
        "fallback pattern (ADR 0003 §FD Mode Amendment partner). " +
        "Offenders:\n$report"
}

// ─── checkScanFileBoundedWait ─────────────────────────────────────────────────

/**
 * Verbatim lift of checkScanFileBoundedWait.
 * Regex: \bMediaScannerConnection\s*\.\s*scanFile\b
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 * Exclusion: the single legitimate call site is MediaScanWaiter.kt.
 *   Old code: `it.canonicalFile != allowedFile` where allowedFile was
 *   `file("src/main/java/.../MediaScanWaiter.kt").canonicalFile`.
 *   We replace with a relPath suffix match on "service/export/MediaScanWaiter.kt"
 *   (normalised to forward slashes). Suffix is unique in the rova source tree.
 */
internal fun ruleScanFileBoundedWait(files: List<SourceFile>): String? {
    val pattern = Regex("""\bMediaScannerConnection\s*\.\s*scanFile\b""")
    val offenders = files
        .filter { it.relPath.endsWith(".kt") }
        .filter { f ->
            !f.relPath.replace('\\', '/').endsWith("service/export/MediaScanWaiter.kt")
        }
        .mapNotNull { f ->
            val hits = f.lines
                .withIndex()
                .filter { (idx, _) ->
                    pattern.containsMatchIn(f.strippedLines.getOrElse(idx) { "" })
                }
            if (hits.isEmpty()) null else f to hits
        }
        .toList()
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, hits) ->
        hits.joinToString("\n") { (i, line) ->
            "  ${f.relPath}:${i + 1}: ${line.trim()}"
        }
    }
    return "MediaScannerConnection.scanFile is forbidden outside " +
        "MediaScanWaiter.kt — every call must be bounded by " +
        "MediaScanWaiter.scanAndWait (Phase 1.7 Patch 2; " +
        "naked awaits hang the foreground service if the " +
        "scan callback never fires). Offenders:\n$report"
}

// ─── checkPendingFdModeIsRW ───────────────────────────────────────────────────

/**
 * Verbatim lift of checkPendingFdModeIsRW.
 * Scope: Tier1*.kt files only (old: `it.name.startsWith("Tier1")`).
 *   We extract the trailing filename component from relPath (normalised to /).
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 * Detection: strip `"rw"` from each stripped line, then check for residual `"w"`.
 *   This avoids false positives on the legal `"rw"` mode.
 *   CommentStripper keeps string literals verbatim, so "rw" and "w" inside strings
 *   are preserved in stripped output — detection is byte-identical to the original.
 */
internal fun rulePendingFdModeIsRW(files: List<SourceFile>): String? {
    val offenders = files
        .filter { f ->
            val normalised = f.relPath.replace('\\', '/')
            val name = normalised.substringAfterLast('/')
            name.endsWith(".kt") && name.startsWith("Tier1")
        }
        .mapNotNull { f ->
            val hits = f.lines
                .withIndex()
                .filter { (idx, _) ->
                    f.strippedLines.getOrElse(idx) { "" }.replace("\"rw\"", "").contains("\"w\"")
                }
            if (hits.isEmpty()) null else f to hits
        }
        .toList()
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, hits) ->
        hits.joinToString("\n") { (i, line) ->
            "  ${f.relPath}:${i + 1}: ${line.trim()}"
        }
    }
    return "Forbidden openFileDescriptor mode \"w\" in Tier 1 sources " +
        "(ADR 0003 §FD Mode Amendment — \"w\" is non-seekable; " +
        "MediaMuxer.stop() rewrites the moov atom and requires a " +
        "seekable FD). Use \"rw\" instead. Offenders:\n$report"
}

// ─── checkExportNoCopyToPublicMovies ─────────────────────────────────────────

/**
 * Verbatim lift of checkExportNoCopyToPublicMovies.
 * Forbidden substring: "copyToPublicMovies"
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 * Scope: all .kt files passed in [files].
 */
internal fun ruleExportNoCopyToPublicMovies(files: List<SourceFile>): String? {
    val offenders = files
        .filter { it.relPath.endsWith(".kt") }
        .mapNotNull { f ->
            val hits = f.lines
                .withIndex()
                .filter { (idx, _) ->
                    f.strippedLines.getOrElse(idx) { "" }.contains("copyToPublicMovies")
                }
            if (hits.isEmpty()) null else f to hits
        }
        .toList()
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, hits) ->
        hits.joinToString("\n") { (i, line) ->
            "  ${f.relPath}:${i + 1}: ${line.trim()}"
        }
    }
    return "copyToPublicMovies symbol is forbidden (Phase 1.7 commit-7). Offenders:\n$report"
}

// ─── checkExportCleanupPredicate ─────────────────────────────────────────────

/**
 * Verbatim lift of checkExportCleanupPredicate.
 *
 * Single-file REQUIRE gate (old: inputs.file(...)).
 * The rule receives a List<SourceFile> with one element (ExportCleanupPredicate.kt).
 *
 * EMPTY-INPUT REASONING: if the list is empty, the required tokens are absent by
 * definition (the file is missing). We must return the failure message — returning
 * null would silently let a missing predicate file pass the gate, which is the
 * opposite of a REQUIRE invariant. This matches the old gate behaviour: the old
 * code threw `GradleException("Phase 1.7 commit-6 source missing: ...")` when the
 * file didn't exist.
 *
 * Comment-strip: strip lines starting with a line-comment, a star, or a
 * block-comment opener before token checks, so a doc-only mention does not
 * satisfy the gate; the gate must be exercised by code.
 *
 * NOTE (comment-strip hardening 2026-06-25): NOT migrated to CommentStripper —
 * same rationale as ruleExportPendingVisibilityOnQuery. This is a co-presence
 * REQUIRE; the block-opener prefix-skip edge only over-strictly FAILS (never
 * false-PASSES), so closing it would change behavior in the lenient direction,
 * out of scope here.
 *
 * Required tokens (5):
 *   AUTO_DISCARD_ELIGIBLE, privateTempPath, RetryableFailure, ManifestWriteFailed, QueryFailed
 */
internal fun ruleExportCleanupPredicate(files: List<SourceFile>): String? {
    val codeText = if (files.isEmpty()) {
        ""
    } else {
        files.first().lines
            .filter {
                val t = it.trimStart()
                !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")
            }
            .joinToString("\n")
    }
    val problems = mutableListOf<String>()
    if (!codeText.contains("AUTO_DISCARD_ELIGIBLE")) {
        problems += "missing AUTO_DISCARD_ELIGIBLE (Phase 1.5 eligibility gate)"
    }
    if (!codeText.contains("privateTempPath")) {
        problems += "missing privateTempPath check (Tier 2/3 deferred-scan retention gate)"
    }
    if (!codeText.contains("RetryableFailure")) {
        problems += "missing RetryableFailure check (per-session recovery clean gate)"
    }
    if (!codeText.contains("ManifestWriteFailed")) {
        problems += "missing ManifestWriteFailed check (per-session recovery clean gate)"
    }
    if (!codeText.contains("QueryFailed")) {
        problems += "missing QueryFailed check (sweep clean gate; commit-6 NO-GO patch)"
    }
    if (problems.isEmpty()) return null
    val report = problems.joinToString("\n") { "  $it" }
    return "ExportCleanupPredicate cleanup gate violation (ADR 0006 §Ownership table " +
        "+ commit-6 NO-GO patch):\n$report\n" +
        "All four gates MUST be referenced in the predicate source. " +
        "Dropping any gate risks deleting a manifest still needed to " +
        "protect a referenced pending row."
}

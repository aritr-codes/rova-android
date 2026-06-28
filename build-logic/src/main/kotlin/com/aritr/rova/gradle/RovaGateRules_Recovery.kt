package com.aritr.rova.gradle

/**
 * Verbatim lifts of five former app/build.gradle.kts inline gate bodies.
 * Mechanical swaps only vs the inline body:
 *   - iterate [files] parameter instead of dir.walkTopDown() / fileTree(...)
 *   - f.lines / f.text / f.relPath instead of readLines() / readText() / relativeTo(rootDir)
 *   - return message instead of throw GradleException(message); return null to pass
 *   - No "dir.exists()" guard — an empty file set means no offenders → null
 *   - Canonical-path exclusion (checkScanTriggerSingleSite): old code compared
 *     f.canonicalFile == allowedFile.canonicalFile. We only have relPath here.
 *     Replaced with a relPath suffix match on "com/aritr/rova/RovaApp.kt" (after
 *     normalising backslashes to forward slashes). This suffix is unique in the
 *     rova source tree (there is exactly one RovaApp.kt), so the behaviour is
 *     byte-identical to the canonical-path compare on both Windows and Linux.
 */

// ─── checkStopNoGetService ────────────────────────────────────────────────────

/**
 * Verbatim lift of checkStopNoGetService.
 * Regex: PendingIntent\s*\.\s*getService\b
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 */
internal fun ruleStopNoGetService(files: List<SourceFile>): String? {
    val pattern = Regex("""PendingIntent\s*\.\s*getService\b""")
    val offenders = files
        .filter { it.relPath.endsWith(".kt") || it.relPath.endsWith(".java") }
        .mapNotNull { f ->
            val hits = f.lines
                .withIndex()
                .filter { (idx, line) ->
                    pattern.containsMatchIn(f.strippedLine(idx))
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
    return "PendingIntent.getService is forbidden — use PendingIntent.getBroadcast " +
        "with a BroadcastReceiver (Android 12+ disallows FGS starts from " +
        "alarm/notification background contexts). Offenders:\n$report"
}

// ─── checkScheduleReceiverNoFgsStart ─────────────────────────────────────────

/**
 * Verbatim lift of checkScheduleReceiverNoFgsStart.
 * Regex: PendingIntent\s*\.\s*getService\b|\bstartForegroundService\s*\(|\bstartService\s*\(
 * Scope: .kt files only (old gate filtered extension == "kt", not "java").
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 */
internal fun ruleScheduleReceiverNoFgsStart(files: List<SourceFile>): String? {
    val pattern = Regex("""PendingIntent\s*\.\s*getService\b|\bstartForegroundService\s*\(|\bstartService\s*\(""")
    val offenders = files
        .filter { it.relPath.endsWith(".kt") }
        .mapNotNull { f ->
            val hits = f.lines
                .withIndex()
                .filter { (idx, line) ->
                    pattern.containsMatchIn(f.strippedLine(idx))
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
    return "Schedule receivers must not start a foreground service (ADR-0027): the " +
        "camera FGS is started only from MainActivity on the user's notification " +
        "tap. Offenders:\n$report"
}

// ─── checkRecoveryNoDeletion ──────────────────────────────────────────────────

/**
 * Verbatim lift of checkRecoveryNoDeletion.
 * Forbidden substrings: ".delete(", ".deleteRecursively(", ".discardSession("
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 * Scope: recovery dir files (.kt/.java) PLUS RovaApp.kt — both passed in [files].
 */
internal fun ruleRecoveryNoDeletion(files: List<SourceFile>): String? {
    val forbidden = listOf(
        ".delete(",
        ".deleteRecursively(",
        ".discardSession(",
    )
    val offenders = files
        .filter { it.relPath.endsWith(".kt") || it.relPath.endsWith(".java") }
        .mapNotNull { f ->
            val hits = f.lines
                .withIndex()
                .filter { (idx, line) ->
                    forbidden.any { f.strippedLine(idx).contains(it) }
                }
            if (hits.isEmpty()) null else f to hits
        }
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, hits) ->
        hits.joinToString("\n") { (i, line) ->
            "  ${f.relPath}:${i + 1}: ${line.trim()}"
        }
    }
    return "Phase 1.5 sources must not call deletion APIs (ADR 0005 — " +
        "Phase 1.5 emits DiscardEligibility flags only; deletion is " +
        "owned by Phase 1.7 post-export-recovery cleanup or by " +
        "explicit user action). Offenders:\n$report"
}

// ─── checkRecoverySegmentRegex ────────────────────────────────────────────────

/**
 * Verbatim lift of checkRecoverySegmentRegex.
 * Forbidden substring: "seg_"
 * NOTE: the original gate had an aspirational inline comment saying "Comment lines
 * are scanned too — a stale rationale referring to seg_ is itself a code-rot signal",
 * but the actual predicate still skipped comment lines. We now detect on
 * f.strippedLines so that block-comment-close lines (e.g. a line whose trimStart
 * begins with the block-close marker followed by real code) are correctly scanned.
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 */
internal fun ruleRecoverySegmentRegex(files: List<SourceFile>): String? {
    val offenders = files
        .filter { it.relPath.endsWith(".kt") || it.relPath.endsWith(".java") }
        .mapNotNull { f ->
            val hits = f.lines
                .withIndex()
                .filter { (idx, line) ->
                    // Comment lines are scanned too — a stale rationale
                    // referring to `seg_` is itself a code-rot signal.
                    f.strippedLine(idx).contains("seg_")
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
    return "`seg_` is forbidden in Phase 1.5 recovery sources — the " +
        "canonical segment filename pattern is `segment_NNNN.mp4` " +
        "(SessionStore.nextSegmentFilename, ADR 0005). " +
        "Offenders:\n$report"
}

// ─── checkScanTriggerSingleSite ───────────────────────────────────────────────

/**
 * Verbatim lift of checkScanTriggerSingleSite.
 * Forbidden: any file OTHER than RovaApp.kt that contains "runRecoveryScan".
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 *
 * Canonical-path swap: old code used
 *   `filter { it.canonicalFile != allowedFile }` where allowedFile was
 *   `file("src/main/java/com/aritr/rova/RovaApp.kt").canonicalFile`
 * We only have relPath. We exclude files whose relPath (normalised to /)
 * ends with "com/aritr/rova/RovaApp.kt". This suffix is unique in the tree.
 */
internal fun ruleScanTriggerSingleSite(files: List<SourceFile>): String? {
    val offenders = files
        .filter { it.relPath.endsWith(".kt") || it.relPath.endsWith(".java") }
        .filter { f ->
            // Exclude RovaApp.kt — the single allowed call site.
            // Normalise separator so this works on both Windows and Linux.
            !f.relPath.replace('\\', '/').endsWith("com/aritr/rova/RovaApp.kt")
        }
        .mapNotNull { f ->
            val hits = f.lines
                .withIndex()
                .filter { (idx, line) ->
                    f.strippedLine(idx).contains("runRecoveryScan")
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
    return "`runRecoveryScan` is owned exclusively by RovaApp; the only " +
        "trigger is RovaApp.triggerRecoveryScanIfNeeded called from " +
        "MainActivity.onCreate (ADR 0005 §Scan Trigger Boundary). " +
        "Offenders:\n$report"
}

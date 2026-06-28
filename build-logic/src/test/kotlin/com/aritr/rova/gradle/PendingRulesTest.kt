package com.aritr.rova.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PendingRulesTest {

    private fun src(relPath: String, body: String) =
        SourceFile(relPath, body.split("\n"), body)

    // ─── checkExportIsPendingGuarded ─────────────────────────────────────────

    @Test
    fun isPendingGuarded_passesWithRequiresApiAnnotation() {
        val body = """
            @RequiresApi(Build.VERSION_CODES.Q)
            fun insertPending() {
                values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkExportIsPendingGuarded", files))
    }

    @Test
    fun isPendingGuarded_passesWithSdkIntGuard() {
        val body = """
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkExportIsPendingGuarded", files))
    }

    @Test
    fun isPendingGuarded_failsWithNoGuard() {
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val body = """values.put(MediaStore.MediaColumns.IS_PENDING, 1)"""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportIsPendingGuarded", files)
        val expected = "IS_PENDING used without SDK gating in service/export/ — " +
            "the file must be annotated @RequiresApi(Build.VERSION_CODES.Q) " +
            "or guard the reference with `Build.VERSION.SDK_INT >= " +
            "Build.VERSION_CODES.Q`. Offenders:\n" +
            "  $relPath:1: ${body.trim()}"
        assertEquals(expected, msg)
    }

    @Test
    fun isPendingGuarded_skipsCommentLines() {
        // All three comment-skip prefixes: //, *, and block-comment opener
        val body = """
            // values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            /*
             * IS_PENDING mention in doc
             */
            /* IS_PENDING block */
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkExportIsPendingGuarded", files))
    }

    @Test
    fun isPendingGuarded_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkExportIsPendingGuarded", emptyList()))
    }

    @Test
    fun exportIsPendingGuarded_catchesAfterInlineBlockComment_whenUnguarded() {
        // Unguarded IS_PENDING hidden after a same-line block comment used to escape.
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val body = "    /* set */ values.put(IS_PENDING, 0)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportIsPendingGuarded", files)
        val expected = "IS_PENDING used without SDK gating in service/export/ — " +
            "the file must be annotated @RequiresApi(Build.VERSION_CODES.Q) " +
            "or guard the reference with `Build.VERSION.SDK_INT >= " +
            "Build.VERSION_CODES.Q`. Offenders:\n" +
            "  $relPath:1: /* set */ values.put(IS_PENDING, 0)"
        assertEquals(expected, msg)
    }

    // ─── checkExportSetIncludePendingGuarded ──────────────────────────────────

    @Test
    fun setIncludePendingGuarded_passesWhenSdkBranchInWindow() {
        // SDK guard within 30 lines of the setIncludePending call
        val body = """
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                MediaStore.setIncludePending(baseUri)
            }
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1AndroidOps.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkExportSetIncludePendingGuarded", files))
    }

    @Test
    fun setIncludePendingGuarded_failsWhenNoSdkBranchInWindow() {
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1AndroidOps.kt"
        val body = "val uri = MediaStore.setIncludePending(baseUri)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportSetIncludePendingGuarded", files)
        val expected = "setIncludePending used without an SDK branch against " +
            "Build.VERSION_CODES.R — must run only on API 29 (deprecated " +
            "and unreliable on API 30+). Offenders:\n" +
            "  $relPath:1: ${body.trim()}"
        assertEquals(expected, msg)
    }

    @Test
    fun setIncludePendingGuarded_skipsCommentLines() {
        val body = """
            // MediaStore.setIncludePending(uri) — do not call
            /*
             * setIncludePending note
             */
            /* setIncludePending block */
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1AndroidOps.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkExportSetIncludePendingGuarded", files))
    }

    @Test
    fun setIncludePendingGuarded_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkExportSetIncludePendingGuarded", emptyList()))
    }

    @Test
    fun exportSetIncludePendingGuarded_catchesAfterInlineBlockComment_whenNoSdkBranch() {
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val body = "    /* x */ builder.setIncludePending(1)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportSetIncludePendingGuarded", files)
        val expected = "setIncludePending used without an SDK branch against " +
            "Build.VERSION_CODES.R — must run only on API 29 (deprecated " +
            "and unreliable on API 30+). Offenders:\n" +
            "  $relPath:1: /* x */ builder.setIncludePending(1)"
        assertEquals(expected, msg)
    }

    // ─── checkExportQueryArgMatchPendingGuarded ───────────────────────────────

    @Test
    fun queryArgMatchPendingGuarded_passesWhenSdkBranchInWindow() {
        val body = """
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bundle.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            }
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1AndroidSweepOps.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkExportQueryArgMatchPendingGuarded", files))
    }

    @Test
    fun queryArgMatchPendingGuarded_failsWhenNoSdkBranchInWindow() {
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1AndroidSweepOps.kt"
        val body = "bundle.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportQueryArgMatchPendingGuarded", files)
        val expected = "QUERY_ARG_MATCH_PENDING used without an SDK branch against " +
            "Build.VERSION_CODES.R — must run only on API 30+ " +
            "(NoSuchFieldError on Q). Offenders:\n" +
            "  $relPath:1: ${body.trim()}"
        assertEquals(expected, msg)
    }

    @Test
    fun queryArgMatchPendingGuarded_skipsCommentLines() {
        val body = "// bundle.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, 1)"
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1AndroidSweepOps.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkExportQueryArgMatchPendingGuarded", files))
    }

    @Test
    fun queryArgMatchPendingGuarded_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkExportQueryArgMatchPendingGuarded", emptyList()))
    }

    @Test
    fun exportQueryArgMatchPendingGuarded_catchesAfterInlineBlockComment_whenNoSdkBranch() {
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val body = "    /* x */ args.putInt(QUERY_ARG_MATCH_PENDING, 1)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportQueryArgMatchPendingGuarded", files)
        val expected = "QUERY_ARG_MATCH_PENDING used without an SDK branch against " +
            "Build.VERSION_CODES.R — must run only on API 30+ " +
            "(NoSuchFieldError on Q). Offenders:\n" +
            "  $relPath:1: /* x */ args.putInt(QUERY_ARG_MATCH_PENDING, 1)"
        assertEquals(expected, msg)
    }

    // ─── checkExportPendingVisibilityOnQuery ──────────────────────────────────

    private fun cleanVisibilityBody() = """
        @RequiresApi(Build.VERSION_CODES.Q)
        fun sweep() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                val pendingUri = MediaStore.setIncludePending(baseUri)
                resolver.query(pendingUri, projection, "IS_PENDING = 1", null, null)
            } else {
                val bundle = Bundle()
                bundle.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                resolver.query(baseUri, projection, bundle, null)
            }
        }
    """.trimIndent()

    @Test
    fun pendingVisibilityOnQuery_passesOnClean() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1AndroidSweepOps.kt",
            cleanVisibilityBody()
        ))
        assertNull(RovaGateRules.run("checkExportPendingVisibilityOnQuery", files))
    }

    @Test
    fun pendingVisibilityOnQuery_failsWhenMissingAllTokens() {
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1AndroidSweepOps.kt"
        // Has resolver.query( but none of the three required tokens
        val body = "resolver.query(baseUri, projection, null, null, null)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportPendingVisibilityOnQuery", files)
        val expected = "Pending-visibility-on-query rule violated in service/export/ — " +
            "any file calling resolver.query(...) must wire BOTH visibility " +
            "mechanisms AND an explicit IS_PENDING = 1 SQL selection. The " +
            "visibility flags alone do not exclude non-pending rows on API 29 " +
            "(setIncludePending = \"include in addition\", not \"only\") and " +
            "OEM MediaStore behavior on MATCH_ONLY varies. " +
            "Offenders:\n" +
            "  $relPath: missing setIncludePending (API 29 visibility)\n" +
            "  $relPath: missing QUERY_ARG_MATCH_PENDING (API 30+ visibility)\n" +
            "  $relPath: missing explicit IS_PENDING = 1 SQL selection (defense in depth — visibility flags alone are not enough)"
        assertEquals(expected, msg)
    }

    @Test
    fun pendingVisibilityOnQuery_passesWhenNoQueryCall() {
        // File has the tokens but no resolver.query( — gate must pass (vacuous)
        val body = """
            val x = setIncludePending(uri)
            val y = QUERY_ARG_MATCH_PENDING
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1AndroidSweepOps.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkExportPendingVisibilityOnQuery", files))
    }

    @Test
    fun pendingVisibilityOnQuery_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkExportPendingVisibilityOnQuery", emptyList()))
    }

    // ─── checkExportPipelineSingleEntry ───────────────────────────────────────

    @Test
    fun pipelineSingleEntry_passesOnExactlyOneCallInRovaRecordingService() {
        val serviceFile = src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            "return ExportPipeline.export(context, mergedFile)"
        )
        val mergerFile = src(
            "app/src/main/java/com/aritr/rova/utils/VideoMerger.kt",
            "fun mergeSegments(segs: List<File>): File { return segs.first() }"
        )
        val exportHelper = src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt",
            "VideoMerger.mergeSegments(segments)"
        )
        assertNull(RovaGateRules.run("checkExportPipelineSingleEntry", listOf(serviceFile, mergerFile, exportHelper)))
    }

    @Test
    fun pipelineSingleEntry_failsWhenZeroCalls() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt",
            "fun export() { /* nothing */ }"
        ))
        val msg = RovaGateRules.run("checkExportPipelineSingleEntry", files)
        assertNotNull(msg)
        assertEquals(
            "Single-entry rule violated for the tier export pipeline (Phase 1.7 commit-7):\n" +
                "  ExportPipeline.export(...) not called anywhere — performMerge must dispatch to the pipeline.\n" +
                "Fix: live publish goes through ExportPipeline.export from " +
                "RovaRecordingService.performMerge ONLY. VideoMerger mux helpers " +
                "are pipeline internals; consumers must live under service/export/.",
            msg
        )
    }

    @Test
    fun pipelineSingleEntry_failsWhenZeroCallsOnEmptyInput() {
        // Empty input = 0 calls = old gate fails
        val msg = RovaGateRules.run("checkExportPipelineSingleEntry", emptyList())
        assertNotNull(msg)
        assert(msg!!.contains("ExportPipeline.export(...) not called anywhere"))
    }

    @Test
    fun pipelineSingleEntry_failsWhenTwoCalls() {
        val relPath1 = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        val relPath2 = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val files = listOf(
            src(relPath1, "return ExportPipeline.export(context, mergedFile)"),
            src(relPath2, "ExportPipeline.export(context, file)")
        )
        val msg = RovaGateRules.run("checkExportPipelineSingleEntry", files)
        assertNotNull(msg)
        assert(msg!!.contains("ExportPipeline.export has more than one call site"))
    }

    @Test
    fun pipelineSingleEntry_failsWhenCallInWrongFile() {
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val files = listOf(src(relPath, "ExportPipeline.export(context, mergedFile)"))
        val msg = RovaGateRules.run("checkExportPipelineSingleEntry", files)
        assertNotNull(msg)
        assert(msg!!.contains("only RovaRecordingService.performMerge may call ExportPipeline.export"))
    }

    @Test
    fun pipelineSingleEntry_failsWhenMuxCallerOutsideExportDir() {
        val serviceFile = src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            "return ExportPipeline.export(context, mergedFile)\nVideoMerger.mergeSegments(segs)"
        )
        val msg = RovaGateRules.run("checkExportPipelineSingleEntry", listOf(serviceFile))
        assertNotNull(msg)
        assert(msg!!.contains("VideoMerger mux callers must live under service/export/"))
    }

    @Test
    fun pipelineSingleEntry_videoMergerFileItselfIsExemptFromInvariant2() {
        // VideoMerger.kt may call its own functions internally without triggering invariant 2
        val mergerFile = src(
            "app/src/main/java/com/aritr/rova/utils/VideoMerger.kt",
            "fun merge() { VideoMerger.mergeSegments(segs) }"
        )
        val serviceFile = src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            "return ExportPipeline.export(context, mergedFile)"
        )
        assertNull(RovaGateRules.run("checkExportPipelineSingleEntry", listOf(mergerFile, serviceFile)))
    }

    // ─── checkSafTargetCommittedBeforeStream ──────────────────────────────────

    @Test
    fun safTargetCommittedBeforeStream_passesWhenCommitPrecedesStream() {
        val body = """
            setExportSafTarget(docUri)
            SafAndroidOps.copyFileToDocument(context, src, docUri)
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/SafExporter.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkSafTargetCommittedBeforeStream", files))
    }

    @Test
    fun safTargetCommittedBeforeStream_failsWhenNoCommitBeforeStream() {
        val relPath = "app/src/main/java/com/aritr/rova/service/export/SafExporter.kt"
        val body = """
            doSomethingElse()
            copyFileToDocument(src, docUri)
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkSafTargetCommittedBeforeStream", files)
        val expected = "ADR-0024 §commit-before-stream violation:\n" +
            "  $relPath:2: SAF stream op without a prior setExportSafTarget commit\n" +
            "The SAF target doc Uri MUST be committed to the manifest before any byte is written to it."
        assertEquals(expected, msg)
    }

    @Test
    fun safTargetCommittedBeforeStream_exemptsSafAndroidOps() {
        // SafAndroidOps.kt holds the raw stream op and must not be flagged
        val body = "openOutputStream(Uri.parse(docUri), \"wt\")?.use { out -> out.write(bytes) }"
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/SafAndroidOps.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkSafTargetCommittedBeforeStream", files))
    }

    @Test
    fun safTargetCommittedBeforeStream_passesWhenNoStreamOp() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/SafExporter.kt",
            "setExportSafTarget(docUri)"
        ))
        assertNull(RovaGateRules.run("checkSafTargetCommittedBeforeStream", files))
    }

    @Test
    fun safTargetCommittedBeforeStream_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkSafTargetCommittedBeforeStream", emptyList()))
    }

    // ─── checkCompletedWriteOnlyFromPerformMerge ──────────────────────────────

    @Test
    fun completedWriteOnlyFromPerformMerge_passesInsidePerformMerge() {
        // markTerminated with COMPLETED inside performMerge body
        val body = """
            private suspend fun performMerge(segs: List<File>) {
                doMerge(segs)
                sessionStore.markTerminated(sid, Terminated.COMPLETED, StopReason.NONE)
            }
            private fun otherFun() {
                doOtherThing()
            }
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkCompletedWriteOnlyFromPerformMerge", files))
    }

    @Test
    fun completedWriteOnlyFromPerformMerge_failsOutsidePerformMerge() {
        val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
        // markTerminated with COMPLETED outside performMerge (in another function).
        // Class-wrapped so members stay 4-space indented after trimIndent, matching
        // the real file — the boundary detector keys on `^    (fun decl)` (verbatim
        // from the old gate); column-0 decls would defeat it.
        val body = """
            class RovaRecordingService {
                private suspend fun performMerge(segs: List<File>) {
                    doMerge(segs)
                }
                private fun otherFun() {
                    sessionStore.markTerminated(sid, Terminated.COMPLETED, StopReason.NONE)
                }
            }
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkCompletedWriteOnlyFromPerformMerge", files)
        assertNotNull(msg)
        assert(msg!!.contains("markTerminated(...,Terminated.COMPLETED,...) outside performMerge body"))
        assert(msg.contains("B7 violation (ADR 0006 §Terminal-Write Ordering)"))
    }

    @Test
    fun completedWriteOnlyFromPerformMerge_failsInNonServiceFile() {
        val relPath = "app/src/main/java/com/aritr/rova/service/export/ExportRecoveryRunner.kt"
        // Without opt-out marker, this is a violation
        val body = "sessionStore.markTerminated(sid, Terminated.COMPLETED, StopReason.NONE)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkCompletedWriteOnlyFromPerformMerge", files)
        val expected = "B7 violation (ADR 0006 §Terminal-Write Ordering) — " +
            "Terminated.COMPLETED writes restricted to performMerge:\n" +
            "  $relPath: line 1: markTerminated(...,Terminated.COMPLETED,...) — only performMerge may write COMPLETED. Add `// completed-write-opt-out: <reason>` for late-terminal reconciliation.\n" +
            "Fix: write COMPLETED only from RovaRecordingService.performMerge. " +
            "Late-terminal reconciliation (ExportRecoveryRunner) must carry " +
            "`// completed-write-opt-out: <reason>` marker."
        assertEquals(expected, msg)
    }

    @Test
    fun completedWriteOnlyFromPerformMerge_passesWithOptOut() {
        // Non-service file with opt-out marker must pass
        val body = """
            // completed-write-opt-out: late-terminal reconciliation
            sessionStore.markTerminated(sid, Terminated.COMPLETED, StopReason.NONE)
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/ExportRecoveryRunner.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkCompletedWriteOnlyFromPerformMerge", files))
    }

    @Test
    fun completedWriteOnlyFromPerformMerge_skipsCommentLines() {
        // Lines inside // or /* */ comments are ignored (CommentStripper).
        val body = """
            // sessionStore.markTerminated(sid, Terminated.COMPLETED, StopReason.NONE)
            /* * markTerminated doc mention Terminated.COMPLETED */
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/ExportRecoveryRunner.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkCompletedWriteOnlyFromPerformMerge", files))
    }

    @Test
    fun completedWriteOnlyFromPerformMerge_passesOnEmptyInput() {
        assertNull(RovaGateRules.run("checkCompletedWriteOnlyFromPerformMerge", emptyList()))
    }

    // ─── hole-close: block-comment-close false-pass ───────────────────────────

    @Test
    fun pipelineSingleEntry_detectsAfterBlockCommentClose() {
        // raw trimStart begins with `*` — legacy skipped it (false-pass on inv2).
        // A file outside service/export/ calling VideoMerger after a block-comment close.
        val serviceFile = src(
            "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt",
            "return ExportPipeline.export(context, mergedFile)"
        )
        val offender = src(
            "app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt",
            "    */ VideoMerger.mergeSegments(segs)"
        )
        val msg = RovaGateRules.run("checkExportPipelineSingleEntry", listOf(serviceFile, offender))
        assertNotNull(msg)
        assert(msg!!.contains("VideoMerger mux callers must live under service/export/"))
    }

    @Test
    fun safTargetCommittedBeforeStream_detectsAfterBlockCommentClose() {
        // raw trimStart begins with `*` — legacy skipped it (false-pass).
        val relPath = "app/src/main/java/com/aritr/rova/service/export/SafExporter.kt"
        val body = "    */ openOutputStream(docUri, \"wt\")"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkSafTargetCommittedBeforeStream", files)
        assertNotNull(msg)
        assert(msg!!.contains("ADR-0024 §commit-before-stream violation"))
    }

    @Test
    fun completedWriteOnlyFromPerformMerge_detectsAfterBlockCommentClose() {
        // raw trimStart begins with `*` — legacy skipped it (false-pass).
        val relPath = "app/src/main/java/com/aritr/rova/service/export/ExportRecoveryRunner.kt"
        val body = "    */ sessionStore.markTerminated(sid, Terminated.COMPLETED, StopReason.NONE)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkCompletedWriteOnlyFromPerformMerge", files)
        assertNotNull(msg)
        assert(msg!!.contains("B7 violation (ADR 0006 §Terminal-Write Ordering)"))
    }

    // ─── round-3 hole-close: required token only in a comment ──────────────────

    @Test
    fun isPendingGuarded_failsWhenRequiresApiOnlyInComment() {
        // FALSE-PASS HOLE (round-3): the @RequiresApi guard exists only in a //
        // comment; the IS_PENDING use in real code is unguarded. hasFileGuard read
        // raw f.text so the commented annotation satisfied the guard. After
        // hasFileGuard moves to f.strippedText the comment is blanked -> offender.
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val body = """
            // @RequiresApi(Build.VERSION_CODES.Q)
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportIsPendingGuarded", files)
        val expected = "IS_PENDING used without SDK gating in service/export/ — " +
            "the file must be annotated @RequiresApi(Build.VERSION_CODES.Q) " +
            "or guard the reference with `Build.VERSION.SDK_INT >= " +
            "Build.VERSION_CODES.Q`. Offenders:\n" +
            "  $relPath:2: values.put(MediaStore.MediaColumns.IS_PENDING, 1)"
        assertEquals(expected, msg)
    }

    @Test
    fun pendingVisibilityOnQuery_failsWhenVisibilityTokensOnlyInComments() {
        // FALSE-PASS HOLE (round-3): both visibility mechanisms exist only in //
        // comments; only the IS_PENDING=1 selection is real. hasIncludePending /
        // hasMatchPending read raw f.text so the commented tokens satisfied the
        // require. After both move to f.strippedText they are blanked -> fail.
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1AndroidSweepOps.kt"
        val body = """
            @RequiresApi(Build.VERSION_CODES.Q)
            fun sweep() {
                // val pendingUri = MediaStore.setIncludePending(baseUri)
                // bundle.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                resolver.query(baseUri, projection, "IS_PENDING = 1", null, null)
            }
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportPendingVisibilityOnQuery", files)
        assertNotNull(msg)
        assert(msg!!.contains("missing setIncludePending (API 29 visibility)"))
        assert(msg.contains("missing QUERY_ARG_MATCH_PENDING (API 30+ visibility)"))
    }

    @Test
    fun pendingVisibilityOnQuery_failsWhenIsPendingFilterOnlyInTrailingComment() {
        // FALSE-PASS HOLE (round-3): the IS_PENDING=1 selection exists only in a
        // trailing // comment on the query line. hasIsPendingFilter scanned
        // nonCommentText, which keeps same-line trailing-comment text, so it matched.
        // After hasIsPendingFilter moves to f.strippedText the trailing comment is
        // blanked -> missing -> fail. (Both visibility tokens are real here.)
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1AndroidSweepOps.kt"
        val body = """
            @RequiresApi(Build.VERSION_CODES.Q)
            fun sweep() {
                val pendingUri = MediaStore.setIncludePending(baseUri)
                bundle.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                resolver.query(baseUri, projection, null, null, null) // IS_PENDING = 1
            }
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportPendingVisibilityOnQuery", files)
        assertNotNull(msg)
        assert(msg!!.contains("missing explicit IS_PENDING = 1 SQL selection"))
    }

    @Test
    fun pendingVisibilityOnQuery_commentedQueryDoesNotTrigger() {
        // round-3 codex-reconcile: a resolver.query( that exists ONLY inside an
        // inline comment on a non-comment-prefix line must NOT trigger the gate.
        // The old nonCommentText trigger kept such lines verbatim and would
        // spuriously fire (then newly-fail once require-tokens moved to
        // strippedText). With the trigger on strippedText the commented query is
        // blanked -> no trigger -> pass.
        val body = """
            val unused = 0 /* resolver.query(uri, proj, null, null, null) */
        """.trimIndent()
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/export/Tier1AndroidSweepOps.kt",
            body
        ))
        assertNull(RovaGateRules.run("checkExportPendingVisibilityOnQuery", files))
    }

    // ─── round-4 hole-close: SDK / commit require only in a comment ────────────

    @Test
    fun setIncludePending_failsWhenSdkGuardOnlyInComment() {
        // round-4: SDK-branch tokens present only in a comment within the ±30 window
        // must NOT satisfy the require (false-PASS on the raw window before migration).
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val body = """
            // Build.VERSION.SDK_INT and Build.VERSION_CODES.R checked upstream
            values.setIncludePending(1)
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportSetIncludePendingGuarded", files)
        val expected = "setIncludePending used without an SDK branch against " +
            "Build.VERSION_CODES.R — must run only on API 29 (deprecated " +
            "and unreliable on API 30+). Offenders:\n" +
            "  $relPath:2: values.setIncludePending(1)"
        assertEquals(expected, msg)
    }

    @Test
    fun queryArgMatchPending_failsWhenSdkGuardOnlyInComment() {
        // round-4: SDK-branch tokens present only in a comment within the ±30 window
        // must NOT satisfy the require (false-PASS on the raw window before migration).
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val body = """
            // Build.VERSION.SDK_INT >= Build.VERSION_CODES.R checked upstream
            bundle.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, 1)
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportQueryArgMatchPendingGuarded", files)
        val expected = "QUERY_ARG_MATCH_PENDING used without an SDK branch against " +
            "Build.VERSION_CODES.R — must run only on API 30+ " +
            "(NoSuchFieldError on Q). Offenders:\n" +
            "  $relPath:2: bundle.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, 1)"
        assertEquals(expected, msg)
    }

    @Test
    fun safTargetCommittedBeforeStream_failsWhenCommitOnlyInComment() {
        // round-4: a setExportSafTarget commit mentioned only in a comment before the
        // stream op must NOT satisfy the require (false-PASS on raw lines.take).
        val relPath = "app/src/main/java/com/aritr/rova/service/export/SafExporter.kt"
        val body = """
            // setExportSafTarget(uri) already called in the caller
            out.copyFileToDocument(srcFile, destUri)
        """.trimIndent()
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkSafTargetCommittedBeforeStream", files)
        val expected = "ADR-0024 §commit-before-stream violation:\n" +
            "  $relPath:2: SAF stream op without a prior setExportSafTarget commit\n" +
            "The SAF target doc Uri MUST be committed to the manifest before any byte is written to it."
        assertEquals(expected, msg)
    }
}

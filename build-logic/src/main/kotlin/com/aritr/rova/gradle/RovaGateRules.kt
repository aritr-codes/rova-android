package com.aritr.rova.gradle

/**
 * The single home for all 46 gate rules, each LIFTED VERBATIM from the former
 * app/build.gradle.kts doLast bodies. A rule returns the exact message the old
 * gate threw, or null to pass. Split into family files (RovaGateRules_*.kt) as
 * it grows; this object only assembles the immutable registry.
 */
object RovaGateRules {

    /** id -> pure rule. Assembled from family maps; immutable, no mutable state. */
    val registry: Map<String, (List<SourceFile>) -> String?> = buildMap {
        put("checkSchedulerNoGetService", ::ruleSchedulerNoGetService)
        put("checkStopNoGetService", ::ruleStopNoGetService)
        put("checkScheduleReceiverNoFgsStart", ::ruleScheduleReceiverNoFgsStart)
        put("checkRecoveryNoDeletion", ::ruleRecoveryNoDeletion)
        put("checkRecoverySegmentRegex", ::ruleRecoverySegmentRegex)
        put("checkScanTriggerSingleSite", ::ruleScanTriggerSingleSite)
        put("checkRecoveryReceiverCounter", ::ruleRecoveryReceiverCounter)
        put("checkAtomicTerminalWriteForbiddenPair", ::ruleAtomicTerminalWriteForbiddenPair)
        put("checkExternalRootShared", ::ruleExternalRootShared)
        put("checkAudioModeFgsTypeMatch", ::ruleAudioModeFgsTypeMatch)
        put("checkFGSStartGuarded", ::ruleFGSStartGuarded)
        put("checkUserStoppedBeforeMerge", ::ruleUserStoppedBeforeMerge)
        put("checkExportTierReadTolerant", ::ruleExportTierReadTolerant)
        put("checkScanFileBoundedWait", ::ruleScanFileBoundedWait)
        put("checkPendingFdModeIsRW", ::rulePendingFdModeIsRW)
        put("checkExportNoCopyToPublicMovies", ::ruleExportNoCopyToPublicMovies)
        put("checkExportCleanupPredicate", ::ruleExportCleanupPredicate)
        put("checkExportIsPendingGuarded", ::ruleExportIsPendingGuarded)
        put("checkExportSetIncludePendingGuarded", ::ruleExportSetIncludePendingGuarded)
        put("checkExportQueryArgMatchPendingGuarded", ::ruleExportQueryArgMatchPendingGuarded)
        put("checkExportPendingVisibilityOnQuery", ::ruleExportPendingVisibilityOnQuery)
        put("checkExportPipelineSingleEntry", ::ruleExportPipelineSingleEntry)
        put("checkSafTargetCommittedBeforeStream", ::ruleSafTargetCommittedBeforeStream)
        put("checkCompletedWriteOnlyFromPerformMerge", ::ruleCompletedWriteOnlyFromPerformMerge)
    }

    fun run(id: String, files: List<SourceFile>): String? {
        val rule = registry[id]
            ?: throw IllegalArgumentException("Unknown gate id: $id")
        return rule(files)
    }
}

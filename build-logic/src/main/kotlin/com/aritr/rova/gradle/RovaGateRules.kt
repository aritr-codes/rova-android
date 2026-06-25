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
    }

    fun run(id: String, files: List<SourceFile>): String? {
        val rule = registry[id]
            ?: throw IllegalArgumentException("Unknown gate id: $id")
        return rule(files)
    }
}

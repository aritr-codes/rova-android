package com.aritr.rova.service.export

/**
 * B5 / ADR-0025 commit-before-finalize — the pure branch choice for resuming
 * an interrupted pre-Q (Tier2/3) move-OUT publish. The Android `File` probes
 * and delete/rename live in [VaultAndroidOps] (a thin, un-unit-tested seam);
 * the DECISION is extracted here so it is JVM-testable (house pattern, cf.
 * `ThermalHysteresis` / `SegmentGateThermal`).
 *
 * Given the committed `.part` pointer ([SessionManifest.pendingMoveOutPath])
 * and two cheap on-disk facts, decide whether the prior aborted run already
 * produced the public file (adopt it — no second copy), left only a stale
 * `.part` (delete it, publish fresh), or left nothing (publish fresh).
 */
sealed interface PreQResumeAction {
    /** No committed in-flight `.part`: a fresh first run — publish normally. */
    object Proceed : PreQResumeAction

    /** A prior run's `.part` survives but no valid target — delete it, publish fresh. */
    data class DeleteStalePartThenProceed(val partPath: String) : PreQResumeAction

    /** The prior run's `renameTo` completed and the target is valid — adopt it, no recopy. */
    data class AdoptExistingTarget(val targetPath: String) : PreQResumeAction
}

object PreQMoveOutResume {
    /**
     * @param committedPartPath the `<name>.mp4.part` path persisted before the
     *   prior run wrote its first byte, or `null` on a fresh publish.
     * @param targetExistsAndAdoptable whether the renamed public file (the
     *   `.part` path minus the `.part` suffix) exists AND is a trustworthy copy
     *   of the source — the caller verifies `length == vaultFile.length()`, not
     *   merely non-empty, so a zero-byte/truncated OR a FOREIGN file that merely
     *   happens to occupy the target path is NOT adopted (codex review).
     * @param partExists whether the `.part` file itself still exists on disk.
     */
    fun decide(
        committedPartPath: String?,
        targetExistsAndAdoptable: Boolean,
        partExists: Boolean,
    ): PreQResumeAction {
        if (committedPartPath == null) return PreQResumeAction.Proceed
        if (targetExistsAndAdoptable) {
            return PreQResumeAction.AdoptExistingTarget(committedPartPath.removeSuffix(".part"))
        }
        if (partExists) return PreQResumeAction.DeleteStalePartThenProceed(committedPartPath)
        return PreQResumeAction.Proceed
    }
}

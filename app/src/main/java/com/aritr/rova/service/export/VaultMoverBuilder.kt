package com.aritr.rova.service.export

import android.content.Context
import com.aritr.rova.data.ExportMutationResult
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.data.VaultState
import java.io.File
import java.io.IOException

/**
 * B5 / ADR-0025 (Task 22) — shared construction of a session-bound
 * [VaultMover] for both the UI (move-in from Library, move-out from the
 * unlocked Vault) and cold-launch recovery resume
 * ([ExportRecoveryRunner]). DRY: the four-effect + four-state-setter
 * wiring (and the suspend↔`() -> Unit` bridge) lives in exactly one
 * place so the UI path and the recovery-resume path can never drift.
 *
 * **All effects are `suspend`.** [VaultMover]'s four state-setter params
 * are `suspend () -> Unit` (matching the four effect params), so the
 * [SessionStore] mutators they call (`setVaultState`, `setVaultMovedOut`,
 * `setExportSafTarget` — all `suspend`) are invoked directly, with no
 * thread-blocking bridge. The whole move runs inside a single coroutine
 * (the VM's `viewModelScope` / the runner's suspend `run()`), and every
 * setter/effect suspends on that same coroutine — no pooled thread is
 * ever blocked.
 *
 * **Scope: single-mode only.** [VaultAndroidOps] resolves a manifest's
 * single-mode pointers (`pendingUri` / `publicTargetPath` /
 * `safTargetDocUri` / `vaultFilePath`). P+L sessions carry per-side
 * pointers with no single-mode equivalent, so the builders gate them
 * out via [isSingleModeMovable]; the call site must not invoke a move
 * for a P+L session.
 */
internal object VaultMoverBuilder {

    /**
     * A P+L session has per-side vault/public pointers that
     * [VaultAndroidOps]'s single-mode dispatchers cannot resolve. Only
     * single-mode sessions are movable through this path.
     */
    fun isSingleModeMovable(manifest: SessionManifest): Boolean =
        manifest.config.captureTopology != "DualShot"

    /**
     * Strict bridge for a state-setter manifest write (codex review).
     * [SessionStore]'s export mutators DON'T throw on a write failure —
     * they return [ExportMutationResult.Failed] / `UnknownSession`. A
     * silently-swallowed terminal write would let `moveOut` report success
     * while the manifest still says UNVAULTING and points at the private
     * vault file — and the caller would then delete that file (data loss).
     * Treat anything but [ExportMutationResult.Wrote] as fatal so the move
     * surfaces the failure (caller catches, reports, and SKIPS cleanup),
     * leaving the session in its intermediate state for a recovery retry.
     */
    private fun requireWrote(label: String, result: ExportMutationResult) {
        if (result !is ExportMutationResult.Wrote) {
            throw IOException("VaultMoverBuilder: $label manifest write did not commit: $result")
        }
    }

    /**
     * Build a move-IN [VaultMover] (PUBLIC → VAULTED). The deterministic
     * vault destination path is computed once up front so the VAULTING
     * state records exactly the on-disk path [VaultAndroidOps.copyToPrivate]
     * writes (recovery can then resume from the persisted pointer).
     *
     * Ordering (enforced by [VaultMover.moveIn]): copyToPrivate →
     * setVaulting(path) → deletePublic → setVaulted.
     *
     * `setVaulted` uses `setVaultStateVaultedAndClearPublic` — it PRESERVES
     * `vaultFilePath` (a leftover public copy stays recoverable) but clears the
     * now-deleted public pointers (`pendingUri` / `publicTargetPath` /
     * `safTargetDocUri`) so the manifest stops pointing at the artifact
     * [deletePublic] just removed (ADR-0025 move-in stale-pointer follow-up).
     */
    fun buildMoveIn(
        context: Context,
        sessionStore: SessionStore,
        manifest: SessionManifest,
        sessionDir: File,
        ops: VaultAndroidOps = VaultAndroidOps(context),
    ): VaultMover {
        val sessionId = manifest.sessionId
        val vaultFile = ops.vaultFileFor(manifest, sessionDir)
        return VaultMover(
            copyToPrivate = { ops.copyToPrivate(manifest, sessionDir) },
            deletePublic = { ops.deletePublic(manifest) },
            // move-in never publishes
            publishExisting = { error("VaultMoverBuilder.buildMoveIn: publishExisting is move-out only") },
            // Records the vault path so a crash-resume in VAULTING finds the file.
            setVaulting = {
                requireWrote(
                    "setVaulting",
                    sessionStore.setVaultState(sessionId, VaultState.VAULTING, vaultFile.absolutePath)
                )
            },
            // Preserves vaultFilePath (recoverable leftover) AND clears the
            // now-deleted public pointers (ADR-0025 move-in stale-pointer fix).
            setVaulted = {
                requireWrote("setVaulted", sessionStore.setVaultStateVaultedAndClearPublic(sessionId))
            },
            setUnvaulting = { error("VaultMoverBuilder.buildMoveIn: setUnvaulting is move-out only") },
            setPublic = { error("VaultMoverBuilder.buildMoveIn: setPublic is move-out only") },
        )
    }

    /**
     * Build a move-OUT [VaultMover] (VAULTED → PUBLIC). The publish step
     * returns the new public pointer ([VaultAndroidOps.PublishOutcome])
     * with exactly one of pendingUri / publicTargetPath / safTargetDocUri
     * set; [setPublic] feeds that EXACT pointer into
     * [SessionStore.setVaultMovedOut]. Because [VaultMover.moveOut] runs
     * `publishExisting` strictly before `setPublic`, the outcome is
     * captured in [outcomeHolder] and read back synchronously.
     *
     * Ordering (enforced by [VaultMover.moveOut]): setUnvaulting →
     * publishExisting → setPublic.
     *
     * @param outcomeHolder one-element holder the publish step fills and
     *   the terminal `setPublic` reads. Caller may inspect it after the
     *   move to learn the new public pointer (e.g. for logging).
     */
    fun buildMoveOut(
        context: Context,
        sessionStore: SessionStore,
        manifest: SessionManifest,
        sessionDir: File,
        outcomeHolder: Array<VaultAndroidOps.PublishOutcome?>,
        ops: VaultAndroidOps = VaultAndroidOps(context),
    ): VaultMover {
        val sessionId = manifest.sessionId
        return VaultMover(
            copyToPrivate = { error("VaultMoverBuilder.buildMoveOut: copyToPrivate is move-in only") },
            deletePublic = { error("VaultMoverBuilder.buildMoveOut: deletePublic is move-in only") },
            publishExisting = {
                outcomeHolder[0] = ops.publishExisting(
                    manifest,
                    sessionDir,
                    setExportSafTarget = { docUri ->
                        // ADR-0024 commit-before-stream: persist the SAF doc Uri
                        // BEFORE the publisher streams the first byte.
                        sessionStore.setExportSafTarget(sessionId, docUri)
                    },
                    // ADR-0025 commit-before-finalize: persist the in-flight
                    // Tier1 pending-row / pre-Q `.part` pointer BEFORE the
                    // irreversible finalize / rename. MANDATORY (requireWrote): a
                    // SILENTLY-failed commit would let the publish finalize with no
                    // dedup pointer, so a crash before setPublic re-opens the exact
                    // duplicate window this fix closes (codex review). On failure
                    // the move aborts BEFORE the irreversible step — the vault file
                    // is intact and the session retries on the next cold launch.
                    setPendingMoveOutTier1 = { uri ->
                        requireWrote("setPendingMoveOutTier1", sessionStore.setPendingMoveOutTier1(sessionId, uri))
                    },
                    setPendingMoveOutPreQ = { path ->
                        requireWrote("setPendingMoveOutPreQ", sessionStore.setPendingMoveOutPreQ(sessionId, path))
                    },
                )
            },
            setVaulting = { error("VaultMoverBuilder.buildMoveOut: setVaulting is move-in only") },
            setVaulted = { error("VaultMoverBuilder.buildMoveOut: setVaulted is move-in only") },
            setUnvaulting = {
                requireWrote("setUnvaulting", sessionStore.setVaultState(sessionId, VaultState.UNVAULTING))
            },
            // Commits PUBLIC + clears vaultFilePath + sets the EXACT pointer publishExisting returned.
            setPublic = {
                val outcome = outcomeHolder[0]
                    ?: error("VaultMoverBuilder.buildMoveOut: setPublic ran before publishExisting populated the outcome")
                requireWrote(
                    "setPublic",
                    sessionStore.setVaultMovedOut(
                        sessionId = sessionId,
                        pendingUri = outcome.pendingUri,
                        publicTargetPath = outcome.publicTargetPath,
                        safTargetDocUri = outcome.safTargetDocUri,
                    )
                )
            },
        )
    }
}

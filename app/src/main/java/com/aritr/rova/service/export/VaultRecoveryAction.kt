package com.aritr.rova.service.export

import com.aritr.rova.data.VaultState

/**
 * B5 / ADR-0025 — recovery keys off vault membership, never exportTier.
 *
 * The cold-launch [ExportRecoveryRunner] decides, per session, what the
 * vault dimension of recovery requires:
 *  - [RESUME_VAULTING] / [RESUME_UNVAULTING] — an in-flight move was
 *    interrupted; recovery must finish the move in its original direction
 *    (spec §6.2). Until the move executors land (Phase 6 / VaultMover) the
 *    runner DEFERS these — it must never run a normal publish for a
 *    half-moved session.
 *  - [MERGE_TO_VAULT] — a vault-intent session never finished its merge;
 *    recovery re-merges to the vault (never publishes).
 *  - [NONE] — no vault action (normal public session, or an
 *    already-[VaultState.VAULTED] artifact of record).
 */
enum class VaultRecoveryAction { NONE, MERGE_TO_VAULT, RESUME_VAULTING, RESUME_UNVAULTING }

/**
 * B5 / ADR-0025 — recovery keys off vault membership, never exportTier.
 * - in-flight move states resume their direction (spec §6.2),
 * - an unfinished vault-intent session re-merges to the vault,
 * - everything else is not a vault action.
 */
fun vaultRecoveryAction(vaultIntentAtStart: Boolean, state: VaultState, finalized: Boolean): VaultRecoveryAction = when {
    state == VaultState.VAULTING -> VaultRecoveryAction.RESUME_VAULTING
    state == VaultState.UNVAULTING -> VaultRecoveryAction.RESUME_UNVAULTING
    vaultIntentAtStart && state != VaultState.VAULTED && !finalized -> VaultRecoveryAction.MERGE_TO_VAULT
    else -> VaultRecoveryAction.NONE
}

package com.aritr.rova.service.export

/**
 * B5 / ADR-0025 — move-in / move-out orchestration. Two-phase through the
 * VAULTING / UNVAULTING intermediates so a crash is always recoverable
 * (spec §9). Ordering law: the destructive step (deletePublic / publish)
 * runs only after the intermediate state is committed, and the terminal
 * state is committed only after the destructive step verifies.
 *
 * All effects are injected so this is JVM-testable. The recovery runner
 * calls [finishVaulting] / [finishUnvaulting] to resume an interrupted
 * move (the private copy / state are already on disk).
 */
internal class VaultMover(
    private val copyToPrivate: suspend () -> Unit,
    private val deletePublic: suspend () -> Unit,
    private val publishExisting: suspend () -> Unit,
    private val setVaulting: suspend () -> Unit,
    private val setVaulted: suspend () -> Unit,
    private val setUnvaulting: suspend () -> Unit,
    private val setPublic: suspend () -> Unit,
) {
    suspend fun moveIn(sessionId: String) {
        copyToPrivate()
        setVaulting()
        finishVaulting(sessionId)
    }

    suspend fun finishVaulting(@Suppress("UNUSED_PARAMETER") sessionId: String) {
        deletePublic()
        setVaulted()
    }

    suspend fun moveOut(sessionId: String) {
        setUnvaulting()
        finishUnvaulting(sessionId)
    }

    suspend fun finishUnvaulting(@Suppress("UNUSED_PARAMETER") sessionId: String) {
        publishExisting()
        setPublic()
    }
}

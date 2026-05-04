package com.aritr.rova.ui.screens

/**
 * Pluggable orchestrator for the History delete pipeline so the
 * delete/discard sequence can be JVM-unit-tested without an
 * `AndroidViewModel`, a real `ContentResolver`, or a real
 * `SessionStore`. Two seams in, one boolean result out.
 *
 * Order contract:
 *   1. Delete the public-gallery artifact via [deleteArtifact].
 *   2. ONLY if step 1 succeeds AND [VideoItem.sessionId] is non-null,
 *      invoke [discardSession] to remove the per-session manifest +
 *      session directory.
 *
 * If [discardSession] throws (`SessionStore.discardSession` itself is
 * defensive but disk failures are possible), the exception is logged
 * via [onDiscardError] and swallowed — the artifact is already gone
 * from the user's gallery, which is the visible part of the
 * operation. A leaked app-private session directory is invisible to
 * the user and at worst burns a few KB of internal storage until the
 * next cleanup pass; counting it as a delete failure would mislead
 * the user into thinking the gallery delete also failed.
 *
 * The helper is `internal` so tests in the same package can access
 * it without exposing it to consumer modules.
 */
internal class HistoryDeleter(
    private val deleteArtifact: (VideoItem) -> Boolean,
    private val discardSession: (String) -> Unit,
    private val onDiscardError: (sessionId: String, t: Throwable) -> Unit = { _, _ -> }
) {
    /**
     * Returns whether the gallery artifact was deleted. The
     * session-metadata cleanup is fire-and-forget after a successful
     * artifact delete and never flips the result to `false`.
     */
    fun delete(item: VideoItem): Boolean {
        val artifactDeleted = deleteArtifact(item)
        val sid = item.sessionId
        if (artifactDeleted && sid != null) {
            try {
                discardSession(sid)
            } catch (t: Throwable) {
                onDiscardError(sid, t)
            }
        }
        return artifactDeleted
    }
}

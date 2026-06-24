package com.aritr.rova.ui.library

/**
 * Builds the keep-set for [LibraryMetadataStore.prune] from durable sources, not the
 * momentarily-visible row set — fixing the orphan-prune bug where a TIER1 row whose MediaStore
 * _DATA query transiently returns null would lose its favorite/title/position.
 */
object PruneKeepSet {
    private const val SESSION_PREFIX = "session:"

    fun build(
        finalizedSessionIds: Collection<String>,
        visibleLegacyKeys: Collection<String?>,
        existingKeys: Collection<String>,
    ): Set<String> {
        val keep = LinkedHashSet<String>()
        for (id in finalizedSessionIds) keep += RecordingIdentity.sessionKey(id)
        for (k in visibleLegacyKeys) if (k != null) keep += k
        // Blanket grace: never prune a not-yet-migrated legacy (non-session) alias.
        for (k in existingKeys) if (!k.startsWith(SESSION_PREFIX)) keep += k
        return keep
    }
}
